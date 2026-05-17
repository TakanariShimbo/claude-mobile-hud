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
 * Phase 3 §3.3.1 の FGS state machine。
 *
 * Glass + Mic の 2 FGS を 1 つの論理サブシステムとして扱い、両者の
 * onCreate / onDestroy が揃ったときだけ state を進める。"片方しか起動して
 * いない過渡状態" を許さないことで FGS 間結合と暗黙状態を排除する (§3.3.1 表参照)。
 *
 * - **`object`** は test しづらいため、Phase 4 では `class` + `AppContainer` に
 *   singleton を置く構成にした (設計書の object は意図伝達用 sketch)。
 * - **`fgsOps`** は FGS 起動 / 停止の delegate を抽象化。Android Context への
 *   依存を一カ所に閉じ込め JVM unit test で fake に差し替え可能 (P3-9 と同思想)。
 * - **`Starting` timeout (5s)**: §3.3.1 異常系。両 ON_CREATE が来ないまま
 *   経過したら Stopping(false) に強制遷移。誤起動した片側 FGS の clean-up は
 *   stopGlassFgs / stopMicFgs を呼ぶ。
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

    // FGS の実 alive 状態を track。両方 true で Starting → Running、両方 false で
    // Stopping(_) → 確定状態に進める。
    private var glassFgsRunning = false
    private var micFgsRunning = false

    /** Starting 状態でだけ走らせる 5s タイムアウト監視 Job。 */
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
            GlassFgsState.Off -> {
                transitionToStarting(context)
            }
            GlassFgsState.Starting, GlassFgsState.Running -> {
                // 冪等。
            }
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
                // restart 予約を取消し。停止続行。
                if (s.restartAfter) {
                    _glassState.value = GlassFgsState.Stopping(restartAfter = false)
                    log.info("glass_restart_cancelled")
                }
            }
        }
    }

    /**
     * shutdown 時に Glass + Channel を順に止める。FGS の ON_DESTROY callback を
     * 待たずに return するので、呼び出し側 (PhoneApplication.onTerminate 等) で
     * 必要なら `glassState`/`channelRunning` を await すること (Phase 4 では
     * Application.onTerminate がエミュレータでしか呼ばれない事情から best-effort)。
     */
    suspend fun shutdownAll(context: Context) {
        stopGlassSession(context)
        stopChannel(context)
    }

    /**
     * GlassConnectionService が自然切断 (CXR-L disconnect) を検出したときに呼ぶ。
     * `Starting` / `Running` → `Stopping(false)` (両 FGS を畳む)。
     */
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

    /**
     * Glass/Mic FGS の onCreate / onDestroy から呼ばれる。両方の ON_CREATE が
     * 揃ったら Starting → Running、両方 ON_DESTROY が揃ったら Stopping → 確定。
     * これ自体は suspend ではなく fire-and-forget (Service のライフサイクル callback
     * を block しない)。
     */
    fun onFgsLifecycle(kind: FgsKind, event: FgsLifecycle, context: Context) {
        scope.launch {
            // P2-4: shutdown 中も lifecycle 通知だけは確実に消化したい。Application scope
            // が cancel 進行中でも mutex 取得とフラグ更新までは NonCancellable で守る。
            withContext(NonCancellable) {
                mutex.withLock {
                    when (kind) {
                        FgsKind.GLASS_CONNECTION -> glassFgsRunning = (event == FgsLifecycle.ON_CREATE)
                        FgsKind.MIC -> micFgsRunning = (event == FgsLifecycle.ON_CREATE)
                        FgsKind.CHANNEL -> {
                            // CHANNEL は Glass 系 state machine とは独立。channelRunning
                            // は startChannel / stopChannel 側で更新済みなのでここでは触らない。
                            return@withLock
                        }
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
                        // P2-1: Starting 中に片方が ON_CREATE → ON_DESTROY と落ちて両 false に
                        // なった (= 片方 FGS が起動直後にクラッシュ等)。5s watchdog を待たず
                        // すぐ Stopping(false) に畳む。残った FGS が居ても stop は idempotent。
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
                        // restart 予約あり: 再度 Starting に持って行く (両 FGS を再起動)。
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
                    // 異常終了: 両 FGS を畳む。片方しか上がっていなくても stop は idempotent。
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

    /**
     * FGS 起動 / 停止の delegate。本番は `RealFgsOperations` で
     * `context.startForegroundService(...)` を叩く。テストは fake。
     */
    interface FgsOperations {
        fun startChannelFgs(context: Context)
        fun stopChannelFgs(context: Context)
        fun startGlassFgs(context: Context)
        fun stopGlassFgs(context: Context)
        fun startMicFgs(context: Context)
        fun stopMicFgs(context: Context)
    }
}
