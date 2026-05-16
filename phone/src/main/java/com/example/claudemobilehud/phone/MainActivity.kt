package com.example.claudemobilehud.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
 * Phone app の唯一の `Activity`。`singleTask` (Manifest) なので 2 つ目のインスタンスは
 * 出ない。
 *
 * **責務**:
 *   - Compose UI (`MainScreen`) の host。
 *   - 通知タップ → `onNewIntent` で extras を読んで該当 session に切替 (§3.6.4)。
 *   - Hi Rokid 認可結果 → 旧 `onActivityResult` で TokenStore.save (P2-C of 4c2 review:
 *     deprecated だが AuthorizationHelper が legacy API しか提供しないため @Suppress)。
 */
class MainActivity : ComponentActivity() {
    private val log = StructuredLog("channel.ui")

    /** 通知タップから来た「次に表示すべき session id」。null 化前提で 1 回消費。 */
    private var pendingSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 起動 intent からも session id を回収 (kill 状態で通知タップ起動された経路)。
        pendingSessionId = intent?.extractSessionId()

        setContent {
            PhoneTheme {
                val viewModel: ChatViewModel = viewModel()
                MainScreen(viewModel)

                // P2-B of 4c2 review: 通知連打で pendingSessionId が上書きされた場合、
                // 旧 LaunchedEffect(target) 経路だと前の select が in-flight のまま
                // 新しい select と race する。snapshotFlow + collectLatest で
                // 「新しい値が来たら前の select を cancel」セマンティクスにする。
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
        // 既存 Task に届いたら session id を 1 回 emit。
        pendingSessionId = intent.extractSessionId()
    }

    @Suppress("DEPRECATION") // AuthorizationHelper still uses legacy startActivityForResult API.
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

    private fun Intent.extractSessionId(): String? =
        getStringExtra(NotificationFactory.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }

    companion object {
        /** Hi Rokid アプリを起動するときの requestCode。GlassDialog から使う。 */
        const val AUTH_REQUEST_CODE = 7777
    }
}
