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

/**
 * アプリを完全終了する前の最終確認。OK で全 FGS を畳んで Activity も task ごと閉じる。
 */
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
                    // P2-H of 4c2 review: `shutdownAll` (suspend) → 完了後に
                    // `finishAndRemoveTask()` の順を守る。逆順だと Activity が消えた
                    // 直後に FGS stop 中の状態で OS が再起動を仕掛けに来る (FGS の
                    // START_STICKY と Activity dispose の race)。`shutdownAll` の中で
                    // FGS の stopForeground / stopSelf を await することで、
                    // finishAndRemoveTask が走った時点でプロセスに残作業が無い。
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
