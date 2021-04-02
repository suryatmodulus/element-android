/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.model.thirdparty.ThirdPartyProtocol
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.thirdparty.GetThirdPartyProtocolsTask
import org.matrix.android.sdk.internal.task.TaskExecutor
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

const val PROTOCOL_PSTN_PREFIXED = "im.vector.protocol.pstn"
const val PROTOCOL_PSTN = "m.protocol.pstn"
const val PROTOCOL_SIP_NATIVE = "im.vector.protocol.sip_native"
const val PROTOCOL_SIP_VIRTUAL = "im.vector.protocol.sip_virtual"


/**
 * This class is responsible for checking if the HS support some protocols for VoIP.
 * As long as the request succeed, it'll check only once by session.
 */
@SessionScope
class CallProtocolsChecker @Inject internal constructor(private val taskExecutor: TaskExecutor,
                                                        private val getThirdPartyProtocolsTask: GetThirdPartyProtocolsTask) {

    interface Listener {
        fun onPSTNSupportUpdated() = Unit
        fun onVirtualRoomSupportUpdated() = Unit
    }

    private var alreadyChecked = AtomicBoolean(false)

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    var supportedPSTNProtocol: String? = null
        private set

    var supportVirtualRooms: Boolean = false
        private set

    fun checkProtocols() {
        if (alreadyChecked.get()) return
        taskExecutor.executorScope.checkThirdPartyProtocols()
    }

    private fun CoroutineScope.checkThirdPartyProtocols() = launch {
        try {
            val protocols = getThirdPartyProtocols(3)
            alreadyChecked.set(true)
            supportedPSTNProtocol = protocols.extractPSTN()
            if (supportedPSTNProtocol != null) {
                listeners.forEach {
                    tryOrNull { it.onPSTNSupportUpdated() }
                }
            }
            supportVirtualRooms = protocols.supportsVirtualRooms()
            if (supportVirtualRooms) {
                listeners.forEach {
                    tryOrNull { it.onVirtualRoomSupportUpdated() }
                }
            }
        } catch (failure: Throwable) {
            Timber.v("Fail to get supported PSTN, will check again next time.")
        }
    }

    private fun Map<String, ThirdPartyProtocol>.extractPSTN(): String? {
        return when {
            containsKey(PROTOCOL_PSTN_PREFIXED) -> PROTOCOL_PSTN_PREFIXED
            containsKey(PROTOCOL_PSTN)          -> PROTOCOL_PSTN
            else                                                    -> null
        }
    }

    private fun Map<String, ThirdPartyProtocol>.supportsVirtualRooms(): Boolean {
      return containsKey(PROTOCOL_SIP_VIRTUAL) && containsKey(PROTOCOL_SIP_NATIVE)
    }

    private suspend fun getThirdPartyProtocols(maxTries: Int): Map<String, ThirdPartyProtocol> {
        return try {
            getThirdPartyProtocolsTask.execute(Unit)
        } catch (failure: Throwable) {
            if (maxTries == 1) {
                throw failure
            } else {
                // Wait for 10s before trying again
                delay(10_000L)
                return getThirdPartyProtocols(maxTries - 1)
            }
        }
    }
}
