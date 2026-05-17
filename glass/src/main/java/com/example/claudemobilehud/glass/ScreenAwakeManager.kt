package com.example.claudemobilehud.glass

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.claudemobilehud.glass.log.StructuredLog

/**
 * グラスの display 電源管理の単一窓口 (Phase 3 §4.5)。
 *
 * 背景: Rokid YodaOS では `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` が CXR-L
 * 経由の reply 受信などをきっかけに事実上無効化されることを確認している。
 * このため画面 ON を維持したい場面では framework の暗黙 user-activity 経路ではなく、
 * `SCREEN_BRIGHT_WAKE_LOCK` を自前で acquire する方式に倒す。
 *
 * 2 つの責務:
 *   - [wakeOnNotification]    : 画面 OFF からの一発 wake-up (ACQUIRE_CAUSES_WAKEUP)
 *   - [acquireWhileStarted]   : Lifecycle.STARTED の間ずっと画面 ON を保持
 *
 * 干渉防止 (会話画面で WakeLock 保持中に通知 wake-up を上書きしない) は
 * [wakeOnNotification] 内の `isInteractive` ガードに閉じ込められている。
 */
object ScreenAwakeManager {
    private val log = StructuredLog("channel.glass.screen-awake")
    private const val NOTIF_WAKE_MS = 3_000L

    // P2-A of 5a review: ON_STOP を経由せず process kill された場合、PowerManager 側に
    // 「無期限 acquire のまま hold」状態が残り得る。10 分 timeout を付け、再 ON_START で
    // 再 acquire される設計に倒す (10 分以内に Activity が再開しなければ表示は落として良い)。
    private const val KEEP_ON_TIMEOUT_MS = 10L * 60 * 1000

    // 通知 wake-up 用の WakeLock を 1 つだけ保持。連続到着で取り直す。
    private var notifWakeLock: PowerManager.WakeLock? = null

    /**
     * 通知到着時の短期 wake-up。画面が既に ON ([PowerManager.isInteractive] = true) なら
     * 何もしない (会話画面側の長期 WakeLock を踏まないため)。
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK は Glass で必須 (上記コメント)。
    fun wakeOnNotification(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) {
            log.debug("wake_skip_interactive")
            return
        }
        notifWakeLock?.takeIf { it.isHeld }?.release()
        notifWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "claude-mobile-hud:notif-wake",
        ).apply {
            setReferenceCounted(false)
            acquire(NOTIF_WAKE_MS)
        }
        log.info("wake_acquire", "ms" to NOTIF_WAKE_MS)
    }

    /** Activity 終了時など、保険で握りっぱなしの通知 WakeLock を解放する。 */
    fun releaseNotificationWake() {
        notifWakeLock?.takeIf { it.isHeld }?.release()
        notifWakeLock = null
    }

    /**
     * 指定 [lifecycle] が STARTED の間ずっと画面 ON を保持する。
     * 呼び出し側は返ってきた [KeepOnHandle.close] を Compose の `DisposableEffect.onDispose`
     * などで必ず呼ぶこと。
     */
    @Suppress("DEPRECATION")
    fun acquireWhileStarted(ctx: Context, lifecycle: Lifecycle): KeepOnHandle {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "claude-mobile-hud:keep-on",
        )?.apply { setReferenceCounted(false) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // P2-A: timeout 付き acquire。ON_START のたびに更新されるので長時間
                // 表示でも生命線が切れない。kill 経路の WakeLock leak を OS 側で自動解除。
                Lifecycle.Event.ON_START -> wl?.takeIf { !it.isHeld }?.acquire(KEEP_ON_TIMEOUT_MS)
                Lifecycle.Event.ON_STOP -> wl?.takeIf { it.isHeld }?.release()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        return KeepOnHandle(wl, observer, lifecycle)
    }

    class KeepOnHandle internal constructor(
        private val wl: PowerManager.WakeLock?,
        private val observer: LifecycleEventObserver,
        private val lifecycle: Lifecycle,
    ) : AutoCloseable {
        override fun close() {
            lifecycle.removeObserver(observer)
            wl?.takeIf { it.isHeld }?.release()
        }
    }
}
