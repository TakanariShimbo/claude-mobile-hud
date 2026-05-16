package com.example.claudemobilehud.phone.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.model.PendingPermission

@Composable
fun PermissionDialog(
    request: PendingPermission,
    remainingCount: Int,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* require an answer */ },
        title = {
            Column {
                Text("ツール承認: ${request.toolName}")
                if (remainingCount > 0) {
                    Text(
                        "+他 $remainingCount 件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        text = {
            Column {
                Text(request.description, style = MaterialTheme.typography.bodyMedium)
                if (request.inputPreview.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            request.inputPreview,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "id: ${request.requestId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = { FilledTonalButton(onClick = onAllow) { Text("許可") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒否") } },
    )
}
