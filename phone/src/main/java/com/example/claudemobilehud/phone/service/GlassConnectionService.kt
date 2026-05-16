package com.example.claudemobilehud.phone.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.log.StructuredLog

/**
 * Phase 3 §3.3.3 の Glass FGS-dataSync スケルトン。
 *
 * 4b1 ではライフサイクル hook (`AppLifecycleController.onFgsLifecycle`) を
 * 配線するだけ。実際の CXR-L 接続 / message 中継は **4c の glass relay 層**
 * (`GlassRelay` / `GlassEventDispatcher` / `AudioRouter`) で本サービスから
 * 駆動される。
 *
 * - 4b1 で OS が "FGS-dataSync が空回り" 検出するか? → start 後ただちに
 *   通知を立て、Service 自体は AppLifecycleController が `stopService`
 *   するまで生存。中身は 4c で埋める。
 * - 自然切断検出時に `onGlassDisconnected` を呼ぶ実装は 4c で追加。
 */
class GlassConnectionService : Service() {
    private val log = StructuredLog("channel.glass")

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
        // P2-5: 通常 Android は Application を先に init するため null にはならない契約。
        // null になるのは ContentProvider 経由起動など本プロジェクト未使用経路のみだが、
        // 起こった場合は state machine が永久 desync するため silent return ではなく
        // 大きな声で error log を出す (controller は 5s watchdog で自己回復する)。
        val app = applicationContext as? PhoneApplication
        if (app?.containerOrNull == null) {
            log.error(
                "fgs_lifecycle_skipped_no_container",
                "event" to event.name,
                "kind" to "glass",
            )
            return
        }
        app.container.lifecycle.onFgsLifecycle(
            AppLifecycleController.FgsKind.GLASS_CONNECTION,
            event,
            applicationContext,
        )
    }

    private fun startForegroundCompat() {
        val notification = NotificationFactory.glassConnection(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationFactory.NOTIF_GLASS_CONNECTION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_GLASS_CONNECTION, notification)
        }
    }
}
