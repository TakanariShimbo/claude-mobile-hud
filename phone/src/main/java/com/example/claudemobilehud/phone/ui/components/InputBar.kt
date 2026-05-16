package com.example.claudemobilehud.phone.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.ui.util.rememberImageBitmapFromPath

/**
 * 入力欄 + ツールバー (画像添付 / マイク / 送信)。Phase 3 §3.5。
 *
 * `pendingImage` が非 null のとき先頭に添付プレビュー行を出す。送信中 (`sending`)
 * は全 IconButton を disable して二重送信を防ぐ。
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onClearAttachment: () -> Unit,
    pendingImage: ImageAttachment?,
    onToggleMic: () -> Unit,
    micAvailable: Boolean,
    micActive: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(8.dp)) {
        pendingImage?.let { AttachmentPreview(it, onClear = onClearAttachment) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPickImage, enabled = enabled && !micActive) {
                Icon(Icons.Default.Image, contentDescription = "画像を添付")
            }
            if (micAvailable) {
                IconButton(onClick = onToggleMic, enabled = enabled) {
                    Icon(
                        imageVector = if (micActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (micActive) "録音停止" else "音声入力",
                        tint = if (micActive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        when {
                            !enabled -> "先に設定を開いてください"
                            micActive -> "聞き取り中..."
                            else -> "メッセージを入力"
                        },
                    )
                },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = enabled && !micActive &&
                    (value.isNotBlank() || pendingImage != null),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
            }
        }
    }
}

@Composable
private fun AttachmentPreview(image: ImageAttachment, onClear: () -> Unit) {
    val bitmap = rememberImageBitmapFromPath(image.localPath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "添付プレビュー",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {}
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "画像を添付済み (${image.mime})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = "添付を解除")
        }
    }
}
