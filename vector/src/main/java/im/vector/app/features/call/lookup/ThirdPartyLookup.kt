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

import im.vector.app.features.call.webrtc.WebRtcCallManager
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.PROTOCOL_SIP_NATIVE
import org.matrix.android.sdk.api.session.call.PROTOCOL_SIP_VIRTUAL
import org.matrix.android.sdk.api.session.thirdparty.model.ThirdPartyUser
import javax.inject.Inject

class ThirdPartyLookup @Inject constructor(private val session: Session,
                                           private val callManager: WebRtcCallManager
) {

    suspend fun pstnLookup(phoneNumber: String): List<ThirdPartyUser> {
        val supportedProtocolKey = callManager.supportedPSTNProtocol ?: throw RuntimeException()
        return tryOrNull {
            session.thirdPartyService().getThirdPartyUser(
                    protocol = supportedProtocolKey,
                    fields = mapOf("m.id.phone" to phoneNumber)
            )
        }.orEmpty()
    }

    suspend fun sipVirtualLookup(nativeMxid: String): List<ThirdPartyUser> {
        return tryOrNull {
            session.thirdPartyService().getThirdPartyUser(
                    protocol = PROTOCOL_SIP_VIRTUAL,
                    fields = mapOf("native_mxid" to nativeMxid)
            )
        }.orEmpty()
    }

    suspend fun sipNativeLookup(virtualMxid: String): List<ThirdPartyUser> {
        return tryOrNull {
            session.thirdPartyService().getThirdPartyUser(
                    protocol = PROTOCOL_SIP_NATIVE,
                    fields = mapOf("virtual_mxid" to virtualMxid)
            )
        }.orEmpty()
    }
}
