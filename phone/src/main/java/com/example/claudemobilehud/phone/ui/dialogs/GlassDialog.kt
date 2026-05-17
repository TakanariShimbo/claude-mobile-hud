package com.example.claudemobilehud.phone.ui.dialogs

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
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
 * Glass 接続管理 dialog (docs/03 §3.5.1.1)。state machine と display の関係、RECORD_AUDIO
 * runtime permission 経路、「認可を解除」の操作順序 (P3-F) は §3.5.1.1 を参照。
 */
@Composable
fun GlassDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val storedToken by TokenStore.token.collectAsStateWithLifecycle()
    val app = context.applicationContext as PhoneApplication
    val lifecycle: AppLifecycleController = app.container.lifecycle
    val glassState by lifecycle.glassState.collectAsStateWithLifecycle()
    val cxrState by GlassConnectionService.connState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch { lifecycle.startGlassSession(context) }
        }
    }

    val hasToken = !storedToken.isNullOrBlank()
    // docs/03 §3.5.1.1: 表示の真実値は lifecycle.glassState、cxrState は補助情報。
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
                    onClick = {
                        val micGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (micGranted) {
                            scope.launch { lifecycle.startGlassSession(context) }
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) { Text("接続") }
            }
        },
        dismissButton = {
            if (hasToken) {
                // docs/03 §3.5.1.1 (P3-F): stop fire-and-forget → clear sync の順番固定。
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
