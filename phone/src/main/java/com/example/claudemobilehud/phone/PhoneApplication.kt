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
 * Phone app の `Application` (docs/03 §3.3.6)。手動 DI、起動順序、`containerOrNull` の
 * 生存判定、MIC FGS eligibility 二重ガードは §3.3.6.1-3.3.6.4 を参照。
 */
class PhoneApplication : Application() {
    private val log = StructuredLog("channel.app")

    @Volatile
    private var _container: AppContainer? = null

    /** docs/03 §3.3.6.2: kill 経路の Receiver から in-proc / out-of-proc 判定に使う。 */
    val containerOrNull: AppContainer? get() = _container

    val container: AppContainer
        get() = _container ?: error("AppContainer accessed before PhoneApplication.onCreate finished")

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
        scope.launch { lifecycle.startChannel(applicationContext) }
        glassRelay.start()
        glassEventDispatcher.start()

        log.info("application_started")
    }

    override fun onTerminate() {
        // docs/03 §3.3.6.3: エミュレータでしか呼ばれない契約。best-effort で shutdownAll。
        applicationScope?.launch { _container?.lifecycle?.shutdownAll(applicationContext) }
        super.onTerminate()
    }

    class AppContainer(
        val repository: ChannelRepository,
        val lifecycle: AppLifecycleController,
        val capsCodec: CapsCodec,
        val glassRelay: GlassRelay,
        val glassEventDispatcher: GlassEventDispatcher,
    )

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
        /** docs/03 §3.3.6.4: MIC FGS eligibility 二重ガードの dispatcher 側 (第一防衛線)。 */
        override fun startMicFgs(context: Context) {
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
