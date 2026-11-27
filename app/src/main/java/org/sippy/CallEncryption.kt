package org.sippy

import org.linphone.core.CallParams
import org.linphone.core.Core
import org.linphone.core.MediaEncryption

/** Apply encryption and its required/optional policy to this call, never to other accounts. */
internal object CallEncryption {
    private val loaded = runCatching { System.loadLibrary("sippy_media") }.isSuccess
    private external fun setMandatory(pointer: Long, mandatory: Boolean): Boolean

    private fun mandatory(params: CallParams, required: Boolean) {
        check(loaded && runCatching { setMandatory(params.nativePointer, required) }.getOrDefault(false)) {
            "The SIP SDK cannot enforce this call's media encryption policy."
        }
    }

    fun apply(core: Core, params: CallParams, encryption: Encryption) {
        val mode = MediaEncryption.valueOf(encryption.name)
        require(core.isMediaEncryptionSupported(mode)) { "${encryption.label} is unavailable in this SIP SDK." }
        params.setMediaEncryption(mode)
        mandatory(params, encryption != Encryption.None)
    }
}
