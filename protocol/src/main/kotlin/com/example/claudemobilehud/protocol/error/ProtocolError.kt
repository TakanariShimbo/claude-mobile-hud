package com.example.claudemobilehud.protocol.error

sealed class ProtocolError(override val message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class DecodeFailed(message: String, cause: Throwable? = null) :
        ProtocolError("decode failed: $message", cause)

    class EncodeFailed(message: String, cause: Throwable? = null) :
        ProtocolError("encode failed: $message", cause)

    class UnknownEventType(val type: String) :
        ProtocolError("unknown event type: $type")
}
