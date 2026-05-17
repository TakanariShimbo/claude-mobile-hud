package com.example.claudemobilehud.protocol.error

/** docs/03 §2.8: codec 失敗用の Throwable。現状 §2.8.1 dead branch を増やさない方針。 */
sealed class ProtocolError(override val message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class DecodeFailed(message: String, cause: Throwable? = null) :
        ProtocolError("decode failed: $message", cause)

    class EncodeFailed(message: String, cause: Throwable? = null) :
        ProtocolError("encode failed: $message", cause)
}
