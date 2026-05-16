package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hub の `pair` CLI が表示する QR の中身をパースする。
 *
 * **payload format** (Hub `hub/src/pair.ts` と shape を一致させる必要あり):
 * ```json
 * {"v": 1, "baseUrl": "http://192.168.1.10:8788", "token": "..."}
 * ```
 * `v` は将来 schema 拡張に備えたバージョン番号。現状 v=1 のみ受理し、未知 version は
 * 明示的に失敗させる (silent ignore よりユーザに「app の更新が必要」と気付かせる方が早い)。
 *
 * `openAiApiKey` は QR には載せない (= 個別端末ごとに手で入れる)。共有 secret は token のみ。
 *
 * 純関数なので JVM 単体テストで網羅する (Android API には触らない)。
 */
object Pairing {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * QR の生文字列を [Settings] に写像する。失敗時は理由を [Throwable.message] に含めて
     * `Result.failure` を返す (UI 側 SettingsDialog で `it.message` を snackbar に出す)。
     *
     * 呼び出し側で `current.copy(baseUrl = ..., token = ...)` のように合成して使う想定で、
     * `openAiApiKey` 等の既存値は保持する。Pairing は **接続情報だけ**を運ぶ責務。
     */
    fun parse(raw: String): Result<PairingResult> = runCatching {
        val payload = json.decodeFromString(QrPayload.serializer(), raw)
        require(payload.v == SUPPORTED_VERSION) {
            "unsupported pairing version: ${payload.v} (この app は v=$SUPPORTED_VERSION のみ対応)"
        }
        // P1-A of 5-6 review: Hub の guard 通過後でも JSON 上で `null` literal が来る可能性
        // (e.g. config token=null での race / 手動 QR) を拾うため、QrPayload は nullable で
        // 受けて parse 側で blank/null を統一エラーにする。
        // P1-B: blank チェックも `require` (IllegalArgumentException) に揃え、`error` (Illegal
        // StateException) と混ぜない。UI 側は message しか見ないので動作は同等だが、将来の
        // catch by type 分岐に強い。
        require(!payload.baseUrl.isNullOrBlank()) { "baseUrl missing in QR" }
        require(!payload.token.isNullOrBlank()) { "token missing in QR" }
        PairingResult(
            baseUrl = payload.baseUrl.trimEnd('/'),
            token = payload.token,
        )
    }

    data class PairingResult(val baseUrl: String, val token: String) {
        /** 既存 Settings に接続情報のみマージして新 Settings を返す。 */
        fun mergeInto(current: Settings): Settings =
            current.copy(baseUrl = baseUrl, token = token)
    }

    private const val SUPPORTED_VERSION = 1

    /**
     * P2-C of 5-6 review: wire key を明示固定する方針で **全フィールドに `@SerialName`** を
     * 付ける。Hub `pair.ts::buildPayload` と key 名が文字レベルで揃っていることを 1 箇所で
     * 担保する。フィールド rename しても wire key が動かない契約。
     * P1-A: `baseUrl` / `token` は nullable で受けて parse 側で blank 統一エラーに正規化する。
     */
    @Serializable
    private data class QrPayload(
        @SerialName("v") val v: Int,
        @SerialName("baseUrl") val baseUrl: String? = null,
        @SerialName("token") val token: String? = null,
    )
}
