package com.example.claudemobilehud.protocol.error

sealed class SharedWireError(open val message: String) {
    sealed class Connection(message: String) : SharedWireError(message) {
        data object NotConfigured : Connection("Settings not configured")
        data class ConnectFailed(val causeMessage: String?) :
            Connection("Connect failed: ${causeMessage ?: "unknown"}")
        data object AuthFailed : Connection("Authentication failed (HTTP 401)")
        data class ServerError(val httpCode: Int, val bodyHead: String) :
            Connection("HTTP $httpCode: $bodyHead")
    }

    sealed class Permission(message: String) : SharedWireError(message) {
        data class Aborted(val requestId: String) :
            Permission("Permission $requestId aborted")
        data object AlreadyVerdicted :
            Permission("Verdict already sent or unknown request")
        data class Unknown(val requestId: String) :
            Permission("Unknown request_id: $requestId")
    }
}
