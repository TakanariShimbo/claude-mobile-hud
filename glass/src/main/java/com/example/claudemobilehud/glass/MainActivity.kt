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

/** Glass app の唯一の Activity (docs/03 §4.5)。 */
class MainActivity : ComponentActivity() {
    private val log = StructuredLog("channel.glass.activity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        GlassBridge.init(applicationContext)
        SoundEffects.init(applicationContext)
        enableEdgeToEdge()

        // docs/03 §4.5.1: 通知 → wake + chime + nav。
        lifecycleScope.launch {
            GlassBridge.notifications.collect { notif ->
                log.info("notification_received", "kind" to notif.kind.name.lowercase())
                ScreenAwakeManager.wakeOnNotification(this@MainActivity)
                SoundEffects.play(this@MainActivity, notif.kind)
                SessionNavigator.requestConversation()
            }
        }

        // docs/03 §4.5.2: 録音停止 sfx。prev == null の初回 emission では鳴らさない。
        lifecycleScope.launch {
            var prev: TranscriptState? = null
            GlassBridge.phoneState.collect { state ->
                val curr = state.transcriptState
                if (prev == TranscriptState.LISTENING && curr != TranscriptState.LISTENING) {
                    SoundEffects.play(this@MainActivity, SoundEffects.Kind.RECORD_STOP)
                }
                prev = curr
            }
        }

        // docs/03 §4.5.2: 送信 sfx。session 切替を検出した emission は baseline 記録のみ。
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
                        // docs/03 §4.5.3: CONVERSATION 滞在中は navigate 抑止
                        // (backstack 再生成で remember 状態を失うため)。
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

    /** docs/03 §4.5.4: 物理リモコン キーマッピング。BACK は完全に乗っ取り Activity finish を回避。 */
    @Suppress("GestureBackNavigation", "RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
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
