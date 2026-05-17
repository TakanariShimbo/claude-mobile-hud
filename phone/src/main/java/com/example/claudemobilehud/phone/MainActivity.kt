package com.example.claudemobilehud.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.phone.service.NotificationFactory
import com.example.claudemobilehud.phone.service.TokenStore
import com.example.claudemobilehud.phone.ui.ChatViewModel
import com.example.claudemobilehud.phone.ui.MainScreen
import com.example.claudemobilehud.phone.ui.theme.PhoneTheme
import com.example.cxrglobal.auth.AuthResult
import com.example.cxrglobal.auth.AuthorizationHelper
import kotlinx.coroutines.flow.collectLatest

/**
 * Phone app の唯一の Activity (docs/03 §3.5.1.3、`singleTask`)。通知連打 race の collectLatest
 * 回避 (P2-B)、POST_NOTIFICATIONS 取得、`onActivityResult` deprecation 取り扱い (P2-C) は
 * §3.5.1.3 を参照。
 */
class MainActivity : ComponentActivity() {
    private val log = StructuredLog("channel.ui")

    private var pendingSessionId by mutableStateOf<String?>(null)

    @Suppress("InvalidFragmentVersionForActivityResult")
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> log.info("post_notifications_grant", "granted" to granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        // kill 状態から通知タップ起動された経路の初回 session id も拾う。
        pendingSessionId = intent?.extractSessionId()

        setContent {
            PhoneTheme {
                val viewModel: ChatViewModel = viewModel()
                MainScreen(viewModel)

                // docs/03 §3.5.1.3 (P2-B): snapshotFlow + collectLatest で通知連打 race を回避。
                LaunchedEffect(Unit) {
                    snapshotFlow { pendingSessionId }
                        .collectLatest { target ->
                            if (target != null) {
                                viewModel.selectSession(target)
                                pendingSessionId = null
                            }
                        }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSessionId = intent.extractSessionId()
    }

    @Suppress("DEPRECATION") // docs/03 §3.5.1.3 (P2-C): AuthorizationHelper が legacy API のみ提供。
    @Deprecated("Migrate to ActivityResultContracts.StartActivityForResult when SDK allows")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST_CODE) {
            when (val r = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
                is AuthResult.AuthSuccess -> {
                    TokenStore.save(applicationContext, r.token)
                    log.info("auth_token_stored", "len" to r.token.length)
                }
                AuthResult.AuthCancel -> log.info("auth_cancelled")
                AuthResult.AuthFail -> log.warn("auth_failed")
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Intent.extractSessionId(): String? =
        getStringExtra(NotificationFactory.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }

    companion object {
        /** Hi Rokid アプリを起動するときの requestCode。GlassDialog から使う。 */
        const val AUTH_REQUEST_CODE = 7777
    }
}
