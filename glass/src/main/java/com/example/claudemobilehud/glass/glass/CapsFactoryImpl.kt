package com.example.claudemobilehud.glass.glass

import com.example.claudemobilehud.protocol.WireEvent
import com.example.claudemobilehud.protocol.codec.CapsFactory
import com.rokid.cxr.Caps
import kotlinx.serialization.json.Json

/**
 * Glass-side `CapsFactory` 実装 (docs/03 §2.5 / §2.5.2)。Phone-side との等価性は §2.5.2.1、
 * Caps fast-path (`encodeToCaps` / `decodeFromCaps`) は §2.5.1 / §2.5.1.1、`runCatching`
 * による decode 防御 (`MsgCallback.onReceive` binder callback の crash 回避) は §2.5.2.3
 * を参照 (Phone-side P1-3 と同根防御)。
 */
class CapsFactoryImpl(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "event"
    },
) : CapsFactory {

    override fun encode(event: WireEvent): ByteArray = encodeToCaps(event).serialize()

    /** docs/03 §2.5.1.1: 送信側 fast-path。`CXRServiceBridge.sendMessage(channel, Caps)` 直渡し用。 */
    fun encodeToCaps(event: WireEvent): Caps {
        val payload = json.encodeToString(WireEvent.serializer(), event)
        val caps = Caps()
        caps.write(KEY_JSON)
        caps.write(payload)
        return caps
    }

    override fun decode(bytes: ByteArray): WireEvent? = runCatching {
        val caps = Caps.fromBytes(bytes) ?: return@runCatching null
        decodeFromCaps(caps)
    }.getOrNull()

    /** docs/03 §2.5.1: 受信側 fast-path。Glass-side onReceive は bytes=null / args=Caps で来る。 */
    fun decodeFromCaps(caps: Caps): WireEvent? = runCatching {
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
