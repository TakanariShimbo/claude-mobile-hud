package com.example.claudemobilehud.phone.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.log.StructuredLog

/**
 * Phase 3 §3.3.4 の Mic FGS-microphone。
 *
 * `AppLifecycleController.startGlassSession` 経由でしか起動しない (companion
 * からの直接 start/stop は提供しない)。POC との差分:
 *   - POC は `MicForegroundService.start(context)` を Repository から直接呼んでいた
 *   - Rev 2 では FGS 操作はすべて AppLifecycleController → FgsOperations を経由
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
