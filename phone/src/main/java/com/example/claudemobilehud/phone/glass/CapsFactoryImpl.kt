package com.example.claudemobilehud.phone.glass

import com.example.claudemobilehud.protocol.WireEvent
import com.example.claudemobilehud.protocol.codec.CapsFactory
import com.rokid.cxr.Caps
import kotlinx.serialization.json.Json

/**
 * Phase 3 §2.5 の `CapsFactory` を Rokid CXR `Caps` で実装する。
 *
 * **wire 構造**: Caps の最初の 2 key-value を `["json"] [JSON 文字列]` で固定。
 *   - 内部 JSON は kotlinx.serialization の `WireEvent` polymorphic encoding。
 *   - `WireEvent` は `@Serializable sealed interface` なので、コンパイラプラグインが
 *     自動で polymorphic serializer を生成する。手動の `SerializersModule` 登録は不要。
 *   - Caps を単純な 2-slot envelope として使う理由は (a) 設計書が `CapsFactory` を
 *     opaque な byte channel とみなしている (b) per-field のキー定義を Phone/Glass 両側で
 *     duplicate するより、`@SerialName` を持つ `WireEvent` を 1 か所で固定するほうが
 *     スキーマ進化を 1 ソースに集約できる。
 *
 * **JsonCodec との整合 (4c1 review P1-2)**:
 *   `:protocol.JsonCodec` と同一の Json 設定 (`classDiscriminator = "event"`,
 *   `encodeDefaults = false`, `explicitNulls = false`) を使う。これにより
 *   SSE 経由 (JsonCodec) と CXR 経由 (CapsFactoryImpl) でペイロードが完全に同型になる。
 *
 * **decode 防御 (P1-3)**:
 *   Rokid Caps の `Value.getString()` は型不整合時に `Caps$IncorrectTypeException`
 *   を投げる。`runCatching` で全 decode 経路をくるみ、malformed payload で binder
 *   callback を crash させない。
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
