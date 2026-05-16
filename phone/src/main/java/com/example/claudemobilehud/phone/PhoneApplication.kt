package com.example.claudemobilehud.phone

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.claudemobilehud.phone.data.ChannelRepository
import com.example.claudemobilehud.phone.glass.BtAudioRouter
import com.example.claudemobilehud.phone.glass.CapsFactoryImpl
import com.example.claudemobilehud.phone.glass.GlassEventDispatcher
import com.example.claudemobilehud.phone.glass.GlassRelay
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.phone.service.AppLifecycleController
import com.example.claudemobilehud.phone.service.ChannelService
import com.example.claudemobilehud.phone.service.GlassConnectionService
import com.example.claudemobilehud.phone.service.MicForegroundService
import com.example.claudemobilehud.phone.service.NotificationFactory
import com.example.claudemobilehud.phone.service.TokenStore
import com.example.claudemobilehud.protocol.codec.CapsCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phone app の `Application` サブクラス。Phase 3 §9.1。
 *
 * - **AppContainer**: 手動 DI。Hilt は本プロジェクト範囲では入れない。
 *   Repository / AppLifecycleController を Application スコープで singleton 化。
 * - **`containerOrNull`**: PermissionActionReceiver が kill 経路判定に使う。
 *   Application は process kill 中も "exists" だが、`container` は onCreate
 *   完了後にしか触れない (== isInitialized 相当)。
 *
 * 起動順序:
 *   1. EncryptedSharedPreferences (TokenStore) を load
 *   2. NotificationFactory.ensureChannels — 通知 channel 作成 (idempotent)
 *   3. AppContainer を build (Repository + lifecycle)
 *   4. Repository.initialize() を applicationScope で suspend 起動
 *      (履歴 / settings 復元)。完了は待たない (UI は uiState の collect で逐次更新)。
 *   5. ChannelService を startForegroundService (Hub 接続常駐)
 */
class PhoneApplication : Application() {
    private val log = StructuredLog("channel.app")

    @Volatile
    private var _container: AppContainer? = null

    /** onCreate 完了後にだけ非 null。生存判定にこちらを使う (PermissionActionReceiver §3.8)。 */
    val containerOrNull: AppContainer? get() = _container

    /** 通常コード経路。kill 中の Receiver からは containerOrNull で安全に確認すること。 */
    val container: AppContainer
        get() = _container ?: error("AppContainer accessed before PhoneApplication.onCreate finished")

    /** Repository などの Application-scope coroutine。kill されるまで生存。 */
    var applicationScope: CoroutineScope? = null
        private set

    override fun onCreate() {
        super.onCreate()
        TokenStore.load(this)
        NotificationFactory.ensureChannels(this)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        applicationScope = scope

        val audioRouter = BtAudioRouter(applicationContext)
        val repository = ChannelRepository(
            applicationContext = applicationContext,
            scope = scope,
            audioRouter = audioRouter,
        )
        val lifecycle = AppLifecycleController(
            fgsOps = RealFgsOperations(),
            scope = scope,
        )
        val capsCodec = CapsCodec(CapsFactoryImpl())
        val glassRelay = GlassRelay(repository, capsCodec)
        val glassEventDispatcher = GlassEventDispatcher(repository, glassRelay, scope)
        _container = AppContainer(
            repository = repository,
            lifecycle = lifecycle,
            capsCodec = capsCodec,
            glassRelay = glassRelay,
            glassEventDispatcher = glassEventDispatcher,
        )

        scope.launch { repository.initialize() }
        // ChannelService を常駐起動。lifecycle 状態は AppLifecycleController.channelRunning に反映。
        scope.launch { lifecycle.startChannel(applicationContext) }
        // Glass relay / dispatcher を Application scope で常駐起動。CXR-L sender が
        // null の間は何も emit しないため早めに start しても副作用は無い。
        glassRelay.start()
        glassEventDispatcher.start()

        log.info("application_started")
    }

    override fun onTerminate() {
        // エミュレータでしか呼ばれない契約 (実機では process kill が先)。
        // 念のため AppLifecycleController.shutdownAll を best-effort で呼ぶ。
        applicationScope?.launch { _container?.lifecycle?.shutdownAll(applicationContext) }
        super.onTerminate()
    }

    /**
     * 手動 DI コンテナ。test では Repository をテストダブルに差し替えて使う。
     */
    class AppContainer(
        val repository: ChannelRepository,
        val lifecycle: AppLifecycleController,
        val capsCodec: CapsCodec,
        val glassRelay: GlassRelay,
        val glassEventDispatcher: GlassEventDispatcher,
    )

    /**
     * AppLifecycleController.FgsOperations 実装。Application Context から FGS を起動 / 停止する。
     * stop は `stopService` を使用 (FGS の自殺は OS-side で startService より弱いため,
     * Application が外部から畳むこの経路では stopService が安定)。
     */
    private class RealFgsOperations : AppLifecycleController.FgsOperations {
        override fun startChannelFgs(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChannelService::class.java),
            )
        }
        override fun stopChannelFgs(context: Context) {
            context.stopService(Intent(context, ChannelService::class.java))
        }
        override fun startGlassFgs(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassConnectionService::class.java),
            )
        }
        override fun stopGlassFgs(context: Context) {
            context.stopService(Intent(context, GlassConnectionService::class.java))
        }
        override fun startMicFgs(context: Context) {
            // FGS-microphone は Android 14+ で RECORD_AUDIO runtime granted + app foreground
            // でなければ `startForeground(MICROPHONE)` が SecurityException で落ちる
            // (targetSDK=36 で更に厳格化)。`startForegroundService` を呼んだ後 5s 以内に
            // `startForeground` を呼ばないと ForegroundServiceDidNotStartInTimeException も
            // 飛ぶので、**dispatcher 段階で eligibility を判定** し、不適合なら
            // `startForegroundService` 自体を呼ばない (POC `MicForegroundService.start()`
            // companion guard と同じ配置)。`MicForegroundService.onCreate` 側にも同じ guard
            // を残しているが、そちらは OS triggered restart 等の double-defense。
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                StructuredLog("channel.lifecycle").warn(
                    "mic_fgs_dispatcher_skipped",
                    "reason" to "record_audio_not_granted",
                )
                return
            }
            val state = ProcessLifecycleOwner.get().lifecycle.currentState
            if (!state.isAtLeast(Lifecycle.State.STARTED)) {
                StructuredLog("channel.lifecycle").warn(
                    "mic_fgs_dispatcher_skipped",
                    "reason" to "app_not_foreground",
                    "state" to state.name,
                )
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, MicForegroundService::class.java),
            )
        }
        override fun stopMicFgs(context: Context) {
            context.stopService(Intent(context, MicForegroundService::class.java))
        }
    }
}
