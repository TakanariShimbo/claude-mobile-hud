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
 * Hub 接続設定 + OpenAI API key の編集 dialog。
 *
 * **4-6 で QR pairing を再実装** (POC からの port):
 *   - 「QR スキャン」ボタンで [QrScanner.scan] を呼び ML Kit Code Scanner を起動。
 *   - 結果を [Pairing.parse] に流して baseUrl / token をフィールドにセット。
 *   - openAiApiKey は QR には乗らない (端末固有 secret なので手入力のまま保持)。
 *   - スキャン失敗 / payload 解釈失敗は dialog 内 inline text で出す (snackbar は dialog 外
 *     なので AlertDialog の中で見せにくい)。
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
    // P3-E of 5-6 review: scanError は config change (回転等) で消えないよう rememberSaveable に。
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
                                // ユーザの能動的キャンセルは「失敗」扱いしない (UX 上の noise)。
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
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("X-Token") },
                    singleLine = true,
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
                enabled = url.isNotBlank() && token.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
