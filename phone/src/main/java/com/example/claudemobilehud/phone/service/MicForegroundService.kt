package com.example.claudemobilehud.phone.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.log.StructuredLog

/**
 * Phase 3 §3.3.4 の Mic FGS-microphone。
 *
 * `AppLifecycleController.startGlassSession` 経由でしか起動しない (FGS 同士の直接
 * 結合を避けるため、外向き API は提供しない)。FGS 操作はすべて
 * AppLifecycleController → FgsOperations を経由する。
 *
 * 役割は AudioRecord を OS から確保し続けるために
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` の通知を立てておくだけ。実際の
 * AudioRecord 駆動は **4b2 の `MicCapture`** が `InputController` 経由で行う。
 */
class MicForegroundService : Service() {
    private val log = StructuredLog("channel.mic")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14+ の FGS-microphone hardening (targetSDK=36 で更に厳格): RECORD_AUDIO
        // runtime 権限取得済み + app が foreground (STARTED 以上) でなければ
        // `startForeground(MICROPHONE)` は SecurityException で死ぬ。事前に GlassDialog
        // で grant を取る運用にしているが、OS triggered restart や mid-session の
        // 権限 revoke 等の edge case で onCreate が走るケースを **defensive に潰す**
        // (起動側 `PhoneApplication.fgsOps.startMicFgs` でも同じ eligibility 判定を
        // しているが、こちらは double-defense として残す)。
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            log.warn("mic_fgs_skipped", "reason" to "record_audio_not_granted")
            stopSelf()
            return
        }
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        if (!state.isAtLeast(Lifecycle.State.STARTED)) {
            log.warn("mic_fgs_skipped", "reason" to "app_not_foreground", "state" to state.name)
            stopSelf()
            return
        }
        startForegroundCompat()
        notifyLifecycle(AppLifecycleController.FgsLifecycle.ON_CREATE)
        log.info("on_create")
    }

    override fun onDestroy() {
        notifyLifecycle(AppLifecycleController.FgsLifecycle.ON_DESTROY)
        log.info("on_destroy")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun notifyLifecycle(event: AppLifecycleController.FgsLifecycle) {
        // P2-5: GlassConnectionService と同じ理由で error log。詳細はそちら参照。
        val app = applicationContext as? PhoneApplication
        if (app?.containerOrNull == null) {
            log.error(
                "fgs_lifecycle_skipped_no_container",
                "event" to event.name,
                "kind" to "mic",
            )
            return
        }
        app.container.lifecycle.onFgsLifecycle(
            AppLifecycleController.FgsKind.MIC,
            event,
            applicationContext,
        )
    }

    private fun startForegroundCompat() {
        val notification = NotificationFactory.mic(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationFactory.NOTIF_MIC,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_MIC, notification)
        }
    }
}
