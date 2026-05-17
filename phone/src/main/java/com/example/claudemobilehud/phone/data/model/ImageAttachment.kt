package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** docs/03 §3.6.5.4: v1.0 は path 形式永続化 (base64 は履歴 JSON 肥大を避けるため非採用)。 */
@Immutable
@Serializable
data class ImageAttachment(
    val localPath: String,
    @SerialName("mime") val mime: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("longest_edge") val longestEdge: Int = 0,
)
