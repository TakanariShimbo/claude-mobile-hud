package com.example.claudemobilehud.protocol.codec

import com.example.claudemobilehud.protocol.WireEvent
import kotlinx.serialization.json.Json

object JsonCodec : Codec {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        // null フィールドを encode 時に省略、decode 時に欠落キーを null と解釈。
        // TS 側で `?: undefined` を毎度書かなくて済むようにする (Phase 3 §2.6 双方向 parity)。
        explicitNulls = false
        classDiscriminator = "event"
    }

    override fun encode(event: WireEvent): ByteArray =
        json.encodeToString(WireEvent.serializer(), event).toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): WireEvent? = runCatching {
        json.decodeFromString(WireEvent.serializer(), bytes.toString(Charsets.UTF_8))
    }.getOrNull()
}
