package com.example.claudemobilehud.phone.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.model.Settings

/**
 * Hub 接続設定 + OpenAI API key の編集 dialog。
 *
 * **QR スキャン未実装**: POC は QrScanner + Pairing.parse を持っていたが本リライト
 * のスコープでは手動入力のみ。Phase 4 後段 (もしくは Phase 5 polish) で別ストーリー
 * として実装予定。
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
