package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hub `pair` CLI の QR payload を [Settings] にマージする pure parser (docs/03 §3.2.6)。
 * payload format / wire key contract / null-blank 正規化は §3.2.6.1-3.2.6.3 を参照。
 */
object Pairing {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Result<PairingResult> = runCatching {
        val payload = json.decodeFromString(QrPayload.serializer(), raw)
        require(payload.v == SUPPORTED_VERSION) {
            "unsupported pairing version: ${payload.v} (この app は v=$SUPPORTED_VERSION のみ対応)"
        }
        require(!payload.baseUrl.isNullOrBlank()) { "baseUrl missing in QR" }
        require(!payload.token.isNullOrBlank()) { "token missing in QR" }
        PairingResult(
            baseUrl = payload.baseUrl.trimEnd('/'),
            token = payload.token,
        )
    }

    data class PairingResult(val baseUrl: String, val token: String) {
        /** 既存 Settings に接続情報のみマージ ([openAiApiKey] 等は保持)。 */
        fun mergeInto(current: Settings): Settings =
            current.copy(baseUrl = baseUrl, token = token)
    }

    private const val SUPPORTED_VERSION = 1

    @Serializable
    private data class QrPayload(
        @SerialName("v") val v: Int,
        @SerialName("baseUrl") val baseUrl: String? = null,
        @SerialName("token") val token: String? = null,
    )
}
