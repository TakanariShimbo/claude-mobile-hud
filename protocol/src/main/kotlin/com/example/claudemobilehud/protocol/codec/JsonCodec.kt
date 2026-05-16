package com.example.claudemobilehud.protocol.codec

import com.example.claudemobilehud.protocol.WireEvent
import kotlinx.serialization.json.Json

object JsonCodec : Codec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "event"
    }

    override fun encode(event: WireEvent): ByteArray =
        json.encodeToString(WireEvent.serializer(), event).toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): WireEvent? = runCatching {
        json.decodeFromString(WireEvent.serializer(), bytes.toString(Charsets.UTF_8))
    }.getOrNull()
}
