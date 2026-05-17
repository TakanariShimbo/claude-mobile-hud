package com.example.claudemobilehud.phone.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.Pairing
import com.example.claudemobilehud.phone.data.QrScanCancelled
import com.example.claudemobilehud.phone.data.QrScanner
import com.example.claudemobilehud.phone.data.model.Settings
import kotlinx.coroutines.launch

/**
 * Hub 接続設定 + OpenAI API key 編集 dialog (docs/03 §3.5.1.2)。QR pairing 経路、
 * scanError の rememberSaveable (P3-E)、token の即時 validation (#189) は §3.5.1.2 を参照。
 */
@Composable
fun SettingsDialog(
    initial: Settings,
    onDismiss: () -> Unit,
    onSave: (Settings) -> Unit,
) {
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var openAiKey by remember { mutableStateOf(initial.openAiApiKey) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接続設定") },
        text = {
            Column {
                Text(
                    "PC の Hub に接続します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    scanError = null
                    scope.launch {
                        runCatching { QrScanner.scan(context) }
                            .onSuccess { raw ->
                                Pairing.parse(raw)
                                    .onSuccess { result ->
                                        url = result.baseUrl
                                        token = result.token
                                    }
                                    .onFailure { e ->
                                        scanError = "QR 解釈失敗: ${e.message ?: e}"
                                    }
                            }
                            .onFailure { e ->
                                // docs/03 §3.2.6.4: 能動キャンセルは UX noise なので error 扱いしない。
                                if (e !is QrScanCancelled) {
                                    scanError = "スキャン失敗: ${e.message ?: e}"
                                }
                            }
                    }
                }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("QR スキャン")
                }
                scanError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.1.10:8788") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                // docs/03 §3.5.1.2: 送信時の AuthFailed 翻訳より前に入力段階で弾く UX 改善 (#189)。
                val tokenError = remember(token) {
                    val invalidIdx = token.indexOfFirst { c ->
                        c.code < 0x20 || c.code > 0x7E
                    }
                    if (invalidIdx >= 0) {
                        "ASCII 印字可能文字 (0x20-0x7E) のみ使えます。" +
                            "貼り付け時に non-ASCII 文字が混入していないか確認してください。"
                    } else {
                        null
                    }
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("X-Token") },
                    singleLine = true,
                    isError = tokenError != null,
                    supportingText = tokenError?.let { msg -> { Text(msg) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "末尾のスラッシュは自動で取り除かれます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "音声入力 (任意)",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = { openAiKey = it },
                    label = { Text("OpenAI API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "設定するとマイクボタンで音声入力できます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            val tokenInvalid = token.any { c -> c.code < 0x20 || c.code > 0x7E }
            FilledTonalButton(
                onClick = {
                    onSave(
                        initial.copy(
                            baseUrl = url.trimEnd('/'),
                            token = token,
                            openAiApiKey = openAiKey,
                        ),
                    )
                },
                enabled = url.isNotBlank() && token.isNotBlank() && !tokenInvalid,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
