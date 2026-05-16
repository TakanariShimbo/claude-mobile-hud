package com.example.claudemobilehud.phone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 添付画像。Phone UI で選択 → Hub に base64 で送る → Bridge で staging。Phase 3 §6.2.4。
 *
 * 永続化対象: outgoing 履歴に紐付けて表示するため。base64 を永続化する代わりに、
 * Phone-local の画像ファイル path だけ持つのが軽い選択肢だが、HistoryStore export
 * (将来) を考えると base64 永続化のほうがポータブル。v1.0 では path 形式。
 */
@Serializable
data class ImageAttachment(
    /** Phone-local の画像ファイル絶対パス (cache or files dir)。 */
    val localPath: String,
    /** MIME (例: "image/jpeg")。 */
    @SerialName("mime") val mime: String,
    /** 元 byte 数 (UI 表示や log 用)。 */
    @SerialName("size_bytes") val sizeBytes: Long,
    /** 元解像度の長辺 (UI hint)。0 なら未測定。 */
    @SerialName("longest_edge") val longestEdge: Int = 0,
)
