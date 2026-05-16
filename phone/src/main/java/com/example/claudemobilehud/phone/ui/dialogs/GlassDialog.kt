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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.claudemobilehud.phone.MainActivity
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.service.AppLifecycleController
import com.example.claudemobilehud.phone.service.GlassConnectionService
import com.example.claudemobilehud.phone.service.GlassCxrState
import com.example.claudemobilehud.phone.service.TokenStore
import com.example.cxrglobal.auth.AuthorizationHelper
import kotlinx.coroutines.launch

/**
 * Glass 接続管理 dialog。CXR-L 認可 → 接続 → 切断のライフサイクル UI。
 *
 * - `TokenStore.token` が null → Hi Rokid 認可ボタンを出す。
 * - token あり / 未接続 → 接続ボタン (= AppLifecycleController.startGlassSession)。
 * - 接続中 → 切断ボタン (= stopGlassSession)。
 */
@Composable
fun GlassDialog(onDismiss: () -> Unit) {
    // P2-D of 4c2 review: PhoneApplication.onCreate で 1 度 load 済みなので
    // dialog 表示の度に main thread で EncryptedSharedPreferences を decrypt しない。
    val context = LocalContext.current
    val storedToken by TokenStore.token.collectAsStateWithLifecycle()
    val app = context.applicationContext as PhoneApplication
    val lifecycle: AppLifecycleController = app.container.lifecycle
    val glassState by lifecycle.glassState.collectAsStateWithLifecycle()
    val cxrState by GlassConnectionService.connState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val hasToken = !storedToken.isNullOrBlank()
    // P2-G of 4c2 review: 表示 label と button 分岐の真実値は lifecycle.glassState に
    // 集約。GlassConnectionService.connState は CXR-L レベルの補助情報として「接続済み /
    // 接続中…」の差を出すためだけに利用する。lifecycle が Stopping/Off なら cxrState は
    // 無視する。
    val running = glassState != AppLifecycleController.GlassFgsState.Off
    val connectionLabel = when {
        glassState == AppLifecycleController.GlassFgsState.Off -> "停止中"
        glassState is AppLifecycleController.GlassFgsState.Stopping -> "停止中…"
        glassState == AppLifecycleController.GlassFgsState.Starting -> "接続中…"
        cxrState == GlassCxrState.CONNECTED -> "接続済み"
        cxrState == GlassCxrState.CONNECTING -> "接続中…"
        else -> "切断"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("グラス接続") },
        text = {
            Column {
                Text(
                    "Hi Rokid アプリで認可してトークンを取得 → グラスに接続。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                Text("認可: ${if (hasToken) "済み" else "未"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "接続: $connectionLabel",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            when {
                !hasToken -> FilledTonalButton(
                    onClick = {
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            runCatching {
                                AuthorizationHelper.requestAuthorization(
                                    activity,
                                    MainActivity.AUTH_REQUEST_CODE,
                                )
                            }
                        }
                    },
                ) { Text("Hi Rokid 認可") }

                running -> FilledTonalButton(
                    onClick = { scope.launch { lifecycle.stopGlassSession(context) } },
                ) { Text("切断") }

                else -> FilledTonalButton(
                    onClick = { scope.launch { lifecycle.startGlassSession(context) } },
                ) { Text("接続") }
            }
        },
        dismissButton = {
            if (hasToken) {
                // P3-F of 4c2 review: 「認可を解除」は
                //   1. 現在 Glass session が動いていれば stopGlassSession を投げる
                //      (suspend → lifecycle が Stopping → Off に遷移)
                //   2. その完了を待たずに TokenStore.clear (EncryptedSharedPreferences)
                //      を即同期で実行する。
                // 順序を入れ替えない理由: token を先に clear すると、stopGlassSession
                // 中に GlassRelay が token を要求するパスで `TokenMissing` が
                // _errors に流れ、ユーザには「解除中なのにエラー」に見える。stop を
                // 先 fire-and-forget → clear (sync) で「stop が in-flight でも token は
                // もう無い」状態に。残作業は lifecycle が watchdog で畳む。
                TextButton(onClick = {
                    if (running) scope.launch { lifecycle.stopGlassSession(context) }
                    TokenStore.clear(context)
                }) { Text("認可を解除") }
            } else {
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
    )
}
