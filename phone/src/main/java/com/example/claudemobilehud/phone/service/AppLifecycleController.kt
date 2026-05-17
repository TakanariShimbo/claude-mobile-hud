package com.example.claudemobilehud.phone.service

import android.content.Context
import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Glass+Mic FGS の state machine (docs/03 §3.3.1)。両 onCreate/onDestroy が
 * 揃ったときだけ state を進めることで、FGS 間の直接結合と過渡状態を排除する。
 *
 * 状態遷移表 / 異常系 / 実装上の不変条件はすべて §3.3.1 に集約。
 */
class AppLifecycleController internal constructor(
    private val fgsOps: FgsOperations,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val startingTimeoutMs: Long = 5_000L,
) {
    private val log = StructuredLog("channel.lifecycle")
    private val mutex = Mutex()

    private val _channelState = MutableStateFlow(false)
    val channelRunning: StateFlow<Boolean> = _channelState.asStateFlow()

    private val _glassState = MutableStateFlow<GlassFgsState>(GlassFgsState.Off)
    val glassState: StateFlow<GlassFgsState> = _glassState.asStateFlow()

    private var glassFgsRunning = false
    private var micFgsRunning = false

    private var startingWatchdog: Job? = null

    suspend fun startChannel(context: Context) = mutex.withLock {
        if (_channelState.value) return@withLock
        fgsOps.startChannelFgs(context)
        _channelState.value = true
        log.info("channel_started")
    }

    suspend fun stopChannel(context: Context) = mutex.withLock {
        if (!_channelState.value) return@withLock
        fgsOps.stopChannelFgs(context)
        _channelState.value = false
        log.info("channel_stopped")
    }

    suspend fun startGlassSession(context: Context) = mutex.withLock {
        when (val s = _glassState.value) {
            GlassFgsState.Off -> transitionToStarting(context)
            GlassFgsState.Starting, GlassFgsState.Running -> Unit
            is GlassFgsState.Stopping -> {
                if (!s.restartAfter) {
                    _glassState.value = GlassFgsState.Stopping(restartAfter = true)
                    log.info("glass_start_queued_during_stopping")
                }
            }
        }
    }

    suspend fun stopGlassSession(context: Context) = mutex.withLock {
        when (val s = _glassState.value) {
            GlassFgsState.Off -> Unit
            GlassFgsState.Starting, GlassFgsState.Running -> {
                cancelStartingWatchdog()
                fgsOps.stopGlassFgs(context)
                fgsOps.stopMicFgs(context)
                _glassState.value = GlassFgsState.Stopping(restartAfter = false)
                log.info("glass_stopping", "from" to s::class.simpleName.orEmpty())
            }
            is GlassFgsState.Stopping -> {
                if (s.restartAfter) {
                    _glassState.value = GlassFgsState.Stopping(restartAfter = false)
                    log.info("glass_restart_cancelled")
                }
            }
        }
    }

    /** FGS の ON_DESTROY callback を待たずに return する best-effort shutdown。 */
    suspend fun shutdownAll(context: Context) {
        stopGlassSession(context)
        stopChannel(context)
    }

    /** GlassConnectionService が CXR-L 自然切断を検出したときに呼ぶ。 */
    fun onGlassDisconnected(context: Context) {
        scope.launch {
            mutex.withLock {
                when (_glassState.value) {
                    GlassFgsState.Off, is GlassFgsState.Stopping -> Unit
                    GlassFgsState.Starting, GlassFgsState.Running -> {
                        cancelStartingWatchdog()
                        fgsOps.stopGlassFgs(context)
                        fgsOps.stopMicFgs(context)
                        _glassState.value = GlassFgsState.Stopping(restartAfter = false)
                        log.info("glass_disconnected_triggers_stop")
                    }
                }
            }
        }
    }

    /** Glass/Mic FGS の onCreate / onDestroy から fire-and-forget で呼ばれる。 */
    fun onFgsLifecycle(kind: FgsKind, event: FgsLifecycle, context: Context) {
        scope.launch {
            // docs/03 §3.3.1 実装上の不変条件: shutdown 中も lifecycle 通知は NonCancellable で完走させる。
            withContext(NonCancellable) {
                mutex.withLock {
                    when (kind) {
                        FgsKind.GLASS_CONNECTION -> glassFgsRunning = (event == FgsLifecycle.ON_CREATE)
                        FgsKind.MIC -> micFgsRunning = (event == FgsLifecycle.ON_CREATE)
                        FgsKind.CHANNEL -> return@withLock
                    }
                    evaluateGlassTransition(context)
                }
            }
        }
    }

    private fun evaluateGlassTransition(context: Context) {
        val both = glassFgsRunning && micFgsRunning
        val none = !glassFgsRunning && !micFgsRunning
        when (val s = _glassState.value) {
            GlassFgsState.Starting -> {
                when {
                    both -> {
                        cancelStartingWatchdog()
                        _glassState.value = GlassFgsState.Running
                        log.info("glass_running")
                    }
                    none -> {
                        // docs/03 §3.3.1 異常系: partial failure 早期 abort。
                        cancelStartingWatchdog()
                        fgsOps.stopGlassFgs(context)
                        fgsOps.stopMicFgs(context)
                        _glassState.value = GlassFgsState.Stopping(restartAfter = false)
                        log.warn("glass_starting_aborted_partial_failure")
                    }
                }
            }
            is GlassFgsState.Stopping -> {
                if (none) {
                    if (s.restartAfter) {
                        transitionToStarting(context)
                    } else {
                        _glassState.value = GlassFgsState.Off
                        log.info("glass_off")
                    }
                }
            }
            else -> Unit
        }
    }

    private fun transitionToStarting(context: Context) {
        _glassState.value = GlassFgsState.Starting
        fgsOps.startGlassFgs(context)
        fgsOps.startMicFgs(context)
        log.info("glass_starting")
        startingWatchdog?.cancel()
        startingWatchdog = scope.launch {
            delay(startingTimeoutMs)
            mutex.withLock {
                if (_glassState.value == GlassFgsState.Starting) {
                    log.warn(
                        "glass_starting_timeout",
                        "glass_fgs" to glassFgsRunning,
                        "mic_fgs" to micFgsRunning,
                    )
                    fgsOps.stopGlassFgs(context)
                    fgsOps.stopMicFgs(context)
                    _glassState.value = GlassFgsState.Stopping(restartAfter = false)
                }
            }
        }
    }

    private fun cancelStartingWatchdog() {
        startingWatchdog?.cancel()
        startingWatchdog = null
    }

    sealed class GlassFgsState {
        data object Off : GlassFgsState()
        data object Starting : GlassFgsState()
        data object Running : GlassFgsState()
        data class Stopping(val restartAfter: Boolean) : GlassFgsState()
    }

    enum class FgsKind { CHANNEL, GLASS_CONNECTION, MIC }
    enum class FgsLifecycle { ON_CREATE, ON_DESTROY }

    /** FGS 起動 / 停止の delegate。本番は `RealFgsOperations`、テストは fake。 */
    interface FgsOperations {
        fun startChannelFgs(context: Context)
        fun stopChannelFgs(context: Context)
        fun startGlassFgs(context: Context)
        fun stopGlassFgs(context: Context)
        fun startMicFgs(context: Context)
        fun stopMicFgs(context: Context)
    }
}
