/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.call.lookup

import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallProtocolsChecker
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.internal.util.awaitCallback
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

const val EVENT_TYPE_VIRTUAL_ROOM = "im.vector.is_virtual_room"

@Singleton
class CallUserMapper @Inject constructor(private val _session: Provider<Session>) {

    private val session: Session
        get() = _session.get()

    private val protocolsChecker: CallProtocolsChecker
        get() = session.callSignalingService().getProtocolsChecker()

    private val virtualRoomIdCache = HashSet<String>()

    fun nativeRoomForVirtualRoom(roomId: String): String? {
        val virtualRoom = session.getRoom(roomId) ?: return null
        val virtualRoomEvent = virtualRoom.getAccountDataEvent(EVENT_TYPE_VIRTUAL_ROOM)
        return virtualRoomEvent?.content?.toModel<RoomVirtualContent>()?.nativeRoom
    }

    suspend fun getOrCreateVirtualRoomForRoom(roomId: String, opponentUserId: String): String? {
        protocolsChecker.awaitCheckProtocols()
        if (!protocolsChecker.supportVirtualRooms) return null
        val virtualUser = userToVirtualUser(opponentUserId) ?: return null
        val virtualRoomId = tryOrNull {
            ensureVirtualRoomExists(virtualUser, roomId)
        } ?: return null
        session.getRoom(virtualRoomId)?.markVirtual(roomId)
        return virtualRoomId
    }

    fun isVirtualRoom(roomId: String): Boolean {
        if (!protocolsChecker.supportVirtualRooms) return false
        if (virtualRoomIdCache.contains(roomId)) return true
        if (nativeRoomForVirtualRoom(roomId) != null) return true
        // also look in the create event for the claimed native room ID, which is the only
        // way we can recognise a virtual room we've created when it first arrives down
        // our stream. We don't trust this in general though, as it could be faked by an
        // inviter: our main source of truth is the DM state.
        val room = session.getRoom(roomId) ?: return false
        val createEvent = room.getStateEvent(EventType.STATE_ROOM_CREATE)
        // we only look at this for rooms we created (so inviters can't just cause rooms
        // to be invisible)
        if (createEvent == null || createEvent.senderId != session.myUserId) return false
        return createEvent.content?.containsKey(EVENT_TYPE_VIRTUAL_ROOM).orFalse()
    }

    suspend fun onNewInvitedRoom(invitedRoomId: String) {
        protocolsChecker.awaitCheckProtocols()
        if (!protocolsChecker.supportVirtualRooms) return
        val invitedRoom = session.getRoom(invitedRoomId) ?: return
        val inviterId = invitedRoom.roomSummary()?.inviterId ?: return
        val nativeLookup = session.sipNativeLookup(inviterId).firstOrNull() ?: return
        if (nativeLookup.fields.containsKey("is_virtual")) {
            val nativeUser = nativeLookup.userId
            val nativeRoomId = session.getExistingDirectRoomWithUser(nativeUser)
            if (nativeRoomId != null) {
                // It's a virtual room with a matching native room, so set the room account data. This
                // will make sure we know where how to map calls and also allow us know not to display
                // it in the future.
                invitedRoom.markVirtual(nativeRoomId)
                // also auto-join the virtual room if we have a matching native room
                // (possibly we should only join if we've also joined the native room, then we'd also have
                // to make sure we joined virtual rooms on joining a native one)
                awaitCallback<Unit> {
                    session.joinRoom(invitedRoomId, callback = it)
                }
            }
            // also put this room in the virtual room ID cache so isVirtualRoom return the right answer
            // in however long it takes for the echo of setAccountData to come down the sync
            virtualRoomIdCache.add(invitedRoom.roomId)
        }
    }

    private suspend fun userToVirtualUser(userId: String): String? {
        val results = session.sipVirtualLookup(userId)
        return results.firstOrNull()?.userId
    }

    private suspend fun Room.markVirtual(nativeRoomId: String) {
        val virtualRoomContent = RoomVirtualContent(nativeRoom = nativeRoomId)
        updateAccountData(EVENT_TYPE_VIRTUAL_ROOM, virtualRoomContent.toContent())
    }

    private suspend fun ensureVirtualRoomExists(userId: String, nativeRoomId: String): String {
        val existingDMRoom = tryOrNull { session.getExistingDirectRoomWithUser(userId) }
        val roomId: String
        if (existingDMRoom != null) {
            roomId = existingDMRoom
        } else {
            val roomParams = CreateRoomParams().apply {
                invitedUserIds.add(userId)
                setDirectMessage()
                creationContent[EVENT_TYPE_VIRTUAL_ROOM] = nativeRoomId
            }
            roomId = awaitCallback {
                session.createRoom(roomParams, it)
            }
        }
        return roomId
    }
}
