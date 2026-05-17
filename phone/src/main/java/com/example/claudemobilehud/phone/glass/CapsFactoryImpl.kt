package com.example.claudemobilehud.phone.glass

import com.example.claudemobilehud.protocol.WireEvent
import com.example.claudemobilehud.protocol.codec.CapsFactory
import com.rokid.cxr.Caps
import kotlinx.serialization.json.Json

/**
 * Phone-side `CapsFactory` 実装 (docs/03 §2.5 / §2.5.2)。2-slot envelope の根拠 (§2.5.2.1)、
 * JsonCodec との設定整合 (§2.5.2.2 P1-2)、decode 防御 (§2.5.2.3 P1-3) を参照。
 */
class CapsFactoryImpl(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "event"
    },
) : CapsFactory {

    override fun encode(event: WireEvent): ByteArray {
        val payload = json.encodeToString(WireEvent.serializer(), event)
        val caps = Caps()
        caps.write(KEY_JSON)
        caps.write(payload)
        return caps.serialize()
    }

    override fun decode(bytes: ByteArray): WireEvent? = runCatching {
        val caps = Caps.fromBytes(bytes) ?: return@runCatching null
        if (caps.size() < 2) return@runCatching null
        val keyValue = caps.at(0) ?: return@runCatching null
        val payloadValue = caps.at(1) ?: return@runCatching null
        if (keyValue.type() != Caps.Value.TYPE_STRING) return@runCatching null
        if (payloadValue.type() != Caps.Value.TYPE_STRING) return@runCatching null
        if (keyValue.string != KEY_JSON) return@runCatching null
        val payload = payloadValue.string ?: return@runCatching null
        json.decodeFromString(WireEvent.serializer(), payload)
    }.getOrNull()

    companion object {
        private const val KEY_JSON = "json"
    }
}
