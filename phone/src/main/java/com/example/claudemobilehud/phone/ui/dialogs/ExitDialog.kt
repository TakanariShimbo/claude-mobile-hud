package com.example.claudemobilehud.phone.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.ui.util.findActivity
import kotlinx.coroutines.launch

/** docs/03 §3.5.1.8: shutdownAll → finishAndRemoveTask の順序 (P2-H, FGS / Activity race 回避)。 */
@Composable
fun ExitDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PhoneApplication
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アプリを終了") },
        text = {
            Text("Hub への接続と常駐通知を停止してアプリを終了します。よろしいですか?")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        app.container.lifecycle.shutdownAll(context)
                        context.findActivity()?.finishAndRemoveTask()
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("終了") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
