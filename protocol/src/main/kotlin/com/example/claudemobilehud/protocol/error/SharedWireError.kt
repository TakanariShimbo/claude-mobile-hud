package com.example.claudemobilehud.protocol.error

/**
 * docs/03 §2.4 / AD-21: Phone / Glass で共有する値型エラー。`Throwable` ではない。
 * §2.4.1 lazy get / §2.4.2 値型 vs Throwable の使い分けを参照。
 */
sealed class SharedWireError {
    abstract val message: String

    sealed class Connection : SharedWireError() {
        data object NotConfigured : Connection() {
            override val message: String get() = "Settings not configured"
        }
        data class ConnectFailed(val causeMessage: String?) : Connection() {
            override val message: String
                get() = "Connect failed: ${causeMessage ?: "unknown"}"
        }
        data object AuthFailed : Connection() {
            override val message: String get() = "Authentication failed (HTTP 401)"
        }
        data class ServerError(val httpCode: Int, val bodyHead: String) : Connection() {
            override val message: String
                get() = "HTTP $httpCode: $bodyHead"
        }
    }

    sealed class Permission : SharedWireError() {
        data class Aborted(val requestId: String) : Permission() {
            override val message: String
                get() = "Permission $requestId aborted"
        }
        data object AlreadyVerdicted : Permission() {
            override val message: String
                get() = "Verdict already sent or unknown request"
        }
        data class Unknown(val requestId: String) : Permission() {
            override val message: String
                get() = "Unknown request_id: $requestId"
        }
    }
}
