package com.example.claudemobilehud.glass

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.claudemobilehud.glass.log.StructuredLog

/**
 * グラスの display 電源管理の単一窓口 (docs/03 §4.10)。FLAG_KEEP_SCREEN_ON を使わない
 * 理由 (§4.10.1)、2 つの責務 (§4.10.2)、isInteractive ガード (§4.10.3)、KEEP_ON_TIMEOUT_MS
 * の根拠 (§4.10.4 P2-A)、KeepOnHandle (§4.10.5) を参照。
 */
object ScreenAwakeManager {
    private val log = StructuredLog("channel.glass.screen-awake")
    private const val NOTIF_WAKE_MS = 3_000L
    private const val KEEP_ON_TIMEOUT_MS = 10L * 60 * 1000

    private var notifWakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION") // docs/03 §4.10.1: SCREEN_BRIGHT_WAKE_LOCK は Glass で必須。
    fun wakeOnNotification(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // docs/03 §4.10.3: 長期 WakeLock との干渉防止。
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

    fun releaseNotificationWake() {
        notifWakeLock?.takeIf { it.isHeld }?.release()
        notifWakeLock = null
    }

    @Suppress("DEPRECATION") // docs/03 §4.10.1: SCREEN_BRIGHT_WAKE_LOCK は Glass で必須。
    fun acquireWhileStarted(ctx: Context, lifecycle: Lifecycle): KeepOnHandle {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "claude-mobile-hud:keep-on",
        )?.apply { setReferenceCounted(false) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // docs/03 §4.10.4 (P2-A): 10 min timeout を ON_START のたびに refresh。
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
