package com.example.claudemobilehud.protocol.error

/**
 * Phone / Glass で共有する error 型 (Phase 3 §2.4 / AD-21)。
 *
 * `abstract val message` + 各サブクラスでの `override` get() による idiomatic な sealed hierarchy。
 * 文字列を get で遅延構築することで、不要な String allocation を毎 instance で払わない + toString
 * の挙動を予測可能にする。
 *
 * NOTE: これは「値型のエラー」であって `Throwable` ではない (UI/log で材料にする想定)。
 * `throw` したい場合は別途 `Exception(message)` でラップする。
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
