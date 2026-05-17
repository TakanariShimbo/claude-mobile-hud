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
 * Mic FGS-microphone (docs/03 §3.3.4)。onCreate の eligibility 二重ガード (§3.3.4.1、
 * §3.3.6.4 第一防衛線の補完) と START_NOT_STICKY 選択理由 (§3.3.4.2) を参照。
 */
class MicForegroundService : Service() {
    private val log = StructuredLog("channel.mic")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // docs/03 §3.3.4.1: 第二防衛線。OS restart / 権限 revoke / dispatcher regression 対策。
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
