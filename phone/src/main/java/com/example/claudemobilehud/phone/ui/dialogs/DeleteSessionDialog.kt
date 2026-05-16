package com.example.claudemobilehud.phone.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeleteSessionDialog(
    sessionLabel: String,
    messageCount: Int,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("セッション $sessionLabel の履歴を削除") },
        text = {
            Column {
                Text(
                    "$messageCount 件のメッセージをこの端末から削除します。" +
                        "PC 側 (Claude のセッションファイル) は影響を受けません。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isActive) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "このセッションは現在アクティブです。Bridge は接続を維持し、" +
                            "新しいメッセージは再び溜まります。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onConfirm) { Text("削除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
