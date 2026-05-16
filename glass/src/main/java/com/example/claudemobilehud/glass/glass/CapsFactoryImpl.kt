package com.example.claudemobilehud.glass.glass

import com.example.claudemobilehud.protocol.WireEvent
import com.example.claudemobilehud.protocol.codec.CapsFactory
import com.rokid.cxr.Caps
import kotlinx.serialization.json.Json

/**
 * Phase 3 §2.5 の `CapsFactory` を Glass 側 (cxr-service-bridge の `Caps`) で実装する。
 *
 * **Phone 側 `phone/...glass/CapsFactoryImpl.kt` との等価性が必須**:
 *   - 同じ Caps envelope (`["json"] [JSON 文字列]`) を使う
 *   - 同じ Json 設定 (`classDiscriminator = "event"`, `encodeDefaults = false`,
 *     `explicitNulls = false`)
 *   これらが揃わないと Phone↔Glass で wire の polymorphic discriminator が drift し、
 *   片側だけで decode に成功する状況が発生する。
 *
 * 重複コード問題 (Phone と同じ実装が 2 か所に居る) は意図的に未抽出:
 *   - 共通モジュール (e.g. :cxrcommon) を切ると `com.rokid.cxr.Caps` 依存が
 *     Phone/Glass の両 SDK (client-l / cxr-service-bridge) に流れて重複クラスの
 *     衝突を AGP が検知する。
 *   - 二重定義のリスクは小さく (Phone/Glass で wire 仕様が分岐したら CI で codec
 *     parity テストが落ちる)、いま抽出する利益が薄い。
 *
 * **decode 防御**: Rokid Caps の `Value.getString()` は型不整合時に
 * `Caps$IncorrectTypeException` を投げるため、全 decode 経路を `runCatching` で
 * くるみ binder callback を crash させない (Phone 側 P1-3 と同じ方針)。
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

    /**
     * Glass 側送信経路は `CXRServiceBridge.sendMessage(channel, Caps)` を取るので、
     * bytes へ落としてから再 parse すると無駄が出る。直接 [Caps] を返す API を
     * 提供して送信経路で使う。
     */
    fun encodeToCaps(event: WireEvent): Caps {
        val payload = json.encodeToString(WireEvent.serializer(), event)
        val caps = Caps()
        caps.write(KEY_JSON)
        caps.write(payload)
        return caps
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
