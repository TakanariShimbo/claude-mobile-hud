package com.example.claudemobilehud.protocol.error

/**
 * Codec 層のエラー。現状 `JsonCodec.decode` は `null` を返すだけで例外を投げないが、
 * 将来 decode の失敗詳細を呼び出し側に渡したくなった場合の受け皿として残す。
 *
 * NOTE: `UnknownEventType` 等の具体サブクラスを作るのは、実際に throw する経路が
 * 用意できてから (Phase 5 以降)。今 dead な sealed branch を増やすと
 * Phone/Glass app 側で永久に発火しない catch を書かせる温床になる。
 */
sealed class ProtocolError(override val message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class DecodeFailed(message: String, cause: Throwable? = null) :
        ProtocolError("decode failed: $message", cause)

    class EncodeFailed(message: String, cause: Throwable? = null) :
        ProtocolError("encode failed: $message", cause)
}
