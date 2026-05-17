package com.example.claudemobilehud.glass

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.claudemobilehud.glass.gesture.GestureBus
import com.example.claudemobilehud.glass.gesture.GlassGesture
import com.example.claudemobilehud.glass.glass.GlassBridge
import com.example.claudemobilehud.glass.log.StructuredLog
import com.example.claudemobilehud.protocol.MessageRole
import com.example.claudemobilehud.protocol.TranscriptState
import com.example.claudemobilehud.glass.ui.PhoneConnectionGate
import com.example.claudemobilehud.glass.ui.SessionNavigator
import com.example.claudemobilehud.glass.ui.nav.GlassNavHost
import com.example.claudemobilehud.glass.ui.nav.GlassRoutes
import com.example.claudemobilehud.glass.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/**
 * Glass app の唯一の `Activity`。Phase 3 §4.4。
 *
 * 責務:
 *   - [GlassBridge.init] で CXR-L 受信を開始 (process singleton)
 *   - Compose UI (`GlassNavHost` + `PhoneConnectionGate`) の host
 *   - 物理リモコンのキーイベントを [GestureBus] に流す (FR-GL-11)
 *   - GlassBridge.notifications を観測 → 画面 wake + chime + nav
 *
 * 通知ハンドラは "音 + 画面 wake + (session-select 画面なら conversation に nav)" のみを
 * 担当する。current session を動かすかどうかの判定は **phone 側** が IDLE のときだけ
 * reply で auto-switch する設計なので、glass からは sendSelectSession を送らない。
 */
class MainActivity : ComponentActivity() {
    private val log = StructuredLog("channel.glass.activity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 画面 OFF / ロック状態でも通知受信時にこの Activity を出せるように (POC 踏襲)。
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        GlassBridge.init(applicationContext)
        enableEdgeToEdge()

        // 通知購読は lifecycleScope に乗せる (Activity 破棄でだけ停止)。
        // 画面 OFF でも process 生存中は coroutine が走り続け、display 電源は
        // ScreenAwakeManager に一任 (干渉ガードも内蔵)。
        lifecycleScope.launch {
            GlassBridge.notifications.collect { notif ->
                log.info("notification_received", "kind" to notif.kind.name.lowercase())
                ScreenAwakeManager.wakeOnNotification(this@MainActivity)
                // P3-D of 5b review: 音の出し分け (reply=1 chime / permission=2 連 chime;
                // FR-GL-71) は `SoundEffects.play` 内に閉じている。ここは kind 不問。
                SoundEffects.play(this@MainActivity, notif.kind)
                // reply: phone 側で IDLE のときだけ current session が当該 session に
                //        switch されており、nav 先は自動的に正しい session の会話画面になる。
                // permission: FR-GL-63 で当該 session が既に current のはずなので、nav は
                //        実質「session select に居れば conversation を出す」操作に等しい。
                // どちらの kind でも nav 試行 → CONVERSATION の場合は LaunchedEffect 内
                // ガードで no-op になる。
                SessionNavigator.requestConversation()
            }
        }

        // UI フィードバック音: 録音開始/停止 を TranscriptState 遷移で検出。
        // 初回 emission (prev == null) では鳴らさない (起動直後の collect で誤発火を防止)。
        lifecycleScope.launch {
            var prev: TranscriptState? = null
            GlassBridge.phoneState.collect { state ->
                val curr = state.transcriptState
                if (prev != null && prev != curr) {
                    when {
                        curr == TranscriptState.LISTENING ->
                            SoundEffects.play(this@MainActivity, SoundEffects.Kind.RECORD_START)
                        prev == TranscriptState.LISTENING ->
                            SoundEffects.play(this@MainActivity, SoundEffects.Kind.RECORD_STOP)
                    }
                }
                prev = curr
            }
        }

        // UI フィードバック音: 送信 を OUTGOING message の最大 id 増加で検出。
        // sessionId と messages を同 wire event (MessagesEvent) 由来の atomic pair で
        // 観測することで、session 切替時の false positive を防ぐ (HistoryStore id は
        // session 横断 autoinc なので、別 session の高 id を「新規送信」と誤認する可能性
        // があり、session-tagged flow なしでは安全に判定できない)。
        // session 変更を検出した emission は baseline 記録のみで音を鳴らさない。
        lifecycleScope.launch {
            var lastSession: String? = null
            var lastOutId: Long? = null
            var initialized = false
            GlassBridge.messagesForSession.collect { (sessId, msgs) ->
                val maxOutId = msgs.asSequence()
                    .filter { it.role == MessageRole.OUTGOING }
                    .maxOfOrNull { it.id }
                if (!initialized || sessId != lastSession) {
                    lastSession = sessId
                    lastOutId = maxOutId
                    initialized = true
                    return@collect
                }
                val prev = lastOutId
                if (prev != null && maxOutId != null && maxOutId > prev) {
                    SoundEffects.play(this@MainActivity, SoundEffects.Kind.SEND)
                }
                lastOutId = maxOutId
            }
        }

        setContent {
            GlassTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneConnectionGate(
                        onExit = { finish() },
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        val nav = rememberNavController()
                        // SessionNavigator 経由の通知由来 nav を捌く。
                        // 既に conversation 画面にいるときは nav.navigate を呼ばない:
                        // popUpTo + launchSingleTop の組合せは「同じ destination でも
                        // navigate すれば NavBackStackEntry が一度 pop → 再生成される」
                        // 挙動になり、ConversationStateHolder の `remember` 状態が消える。
                        LaunchedEffect(nav) {
                            SessionNavigator.requests.collect {
                                if (nav.currentDestination?.route == GlassRoutes.CONVERSATION) {
                                    return@collect
                                }
                                nav.navigate(GlassRoutes.CONVERSATION) {
                                    popUpTo(GlassRoutes.SESSION_SELECT) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                        GlassNavHost(nav = nav, onExit = { finish() })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ScreenAwakeManager.releaseNotificationWake()
        super.onDestroy()
    }

    /**
     * Rokid 物理リモコンのキーマッピング (POC 踏襲):
     *  - ENTER (中央押し)   → Tap
     *  - DPAD_RIGHT        → SwipeForward
     *  - DPAD_LEFT         → SwipeBack
     *  - BACK              → DoubleTap (= 戻る)
     *
     * lint suppression:
     * - GestureBackNavigation: 物理リモコンの BACK キーを意図的に乗っ取って画面内 nav に
     *   流用するので、OnBackPressedDispatcher 経由ではなくここで処理する。
     * - RestrictedApi: super.dispatchKeyEvent はオーバーライドする側からの呼び出しが標準的
     *   イディオムだが、androidx の `@RestrictTo(LIBRARY_GROUP_PREFIX)` で過剰検知される。
     */
    @Suppress("GestureBackNavigation", "RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // P3-C of 5b review: ACTION_UP / LONG_PRESS は emit せず握りつぶす。
            // super.dispatchKeyEvent に流すと OnBackPressedDispatcher が走り画面外遷移
            // (Activity finish) を引き起こすので、BACK イベントは完全に乗っ取る必要がある。
            if (event.action == KeyEvent.ACTION_DOWN) {
                GestureBus.emit(GlassGesture.DoubleTap)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val gesture = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> GlassGesture.Tap
            KeyEvent.KEYCODE_DPAD_RIGHT -> GlassGesture.SwipeForward
            KeyEvent.KEYCODE_DPAD_LEFT -> GlassGesture.SwipeBack
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            else -> return super.dispatchKeyEvent(event)
        }
        GestureBus.emit(gesture)
        return true
    }
}
