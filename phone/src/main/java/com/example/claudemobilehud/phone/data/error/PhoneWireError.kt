package com.example.claudemobilehud.phone.data.error

/** docs/03 §3.7: SharedWireError は Phone 直接受け、ここでは Send / Transcription / Glass を追加。 */
sealed class PhoneWireError {
    sealed class Send : PhoneWireError() {
        data class ImageTooLarge(val actualBytes: Long, val limitBytes: Long) : Send()
        data class SessionNotActive(val sessionId: String) : Send()
        data object Cancelled : Send()
    }

    sealed class Transcription : PhoneWireError() {
        data object ApiKeyMissing : Transcription()
        data object ApiKeyInvalid : Transcription()
        data object MicPermissionDenied : Transcription()
        data class NetworkFailed(val causeMessage: String?) : Transcription()
        data class ServiceError(val message: String) : Transcription()
    }

    sealed class Glass : PhoneWireError() {
        data object TokenMissing : Glass()
        data object HiRokidNotInstalled : Glass()
        data class CxrConnectFailed(val causeMessage: String?) : Glass()
        data object BtScoUnavailable : Glass()
    }
}
