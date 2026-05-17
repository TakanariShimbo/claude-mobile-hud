package com.example.claudemobilehud.phone.data.transcription

import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * `MicCapture` + `TranscriptionWs` を束ねた transcription facade。Phase 3 §3.2.5。
 *
 * 公開 flow:
 *   - [state]: `Idle` / `Connecting` / `Listening(partial)` / `Error(message)`
 *   - [finalized]: completed transcript の確定文字列 (1 文単位)。
 *
 * **同期 vs 非同期** (P1-1, P1-2 対応):
 *   - `start` / `stop` は UI スレッドから呼ばれることを前提に状態遷移を **同期** に行う
 *     (scope.launch を介さない)。これにより呼び出し直後に `state.value` が更新される。
 *   - 副作用 (collect 等) のみ scope.launch で実行する。
 *   - `start → stop → start` を高速連打した場合の race は [generation] (AtomicInteger) を
 *     bump し、遅延到着した event / frame を生成番号で gating することで防ぐ (P1-4)。
 *
 * pre-buffer 戦略 (§3.2.5):
 *   - WS 接続成功直後の `session.update` ack まで音声を送ると drop されることがある。
 *   - SessionReady を受けるまで MicCapture からのフレームを内部 `ArrayDeque` に貯める。
 *   - **drop policy** は `pollLast` (新しい方を捨てる) — pre-buffer の目的は session 冒頭
 *     の発話を残すこと。`pollFirst` だと冒頭を捨てる逆効果になる (P2-3 fix)。
 *   - 上限 250 frame (~10s @ 40ms)。
 *
 * **テスト seam**: `transportFactory` / `micFactory` を差し替えて JVM unit test 可能。
 */
class TranscriptionClient internal constructor(
    private val transportFactory: (TranscriptionConfig) -> TranscriptionTransport,
    private val micFactory: (TranscriptionConfig, CoroutineScope) -> MicCaptureSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor() : this(
        transportFactory = { config -> TranscriptionWs(config) },
        micFactory = { config, scope -> MicCapture(config, scope) },
    )

    /**
     * Application scope を共有したいときの便利コンストラクタ (P2-2 fix)。
     * InputController から呼ばれ、子 scope を介して lifecycle 連動させる。
     */
    constructor(scope: CoroutineScope) : this(
        transportFactory = { config -> TranscriptionWs(config) },
        micFactory = { config, scope2 -> MicCapture(config, scope2) },
        scope = scope,
    )

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Listening(val partial: String) : State
        data class Error(val message: String) : State
    }

    private val log = StructuredLog("channel.transcription")

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // emit を block しないよう extraBufferCapacity を大きめに確保。
    // event 経路 (`onEvent`) からは scope.launch 経由で emit して、collector の
    // backpressure が event 流通を止めないようにする (P2-4)。
    private val _finalized = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val finalized: SharedFlow<String> = _finalized.asSharedFlow()

    // ライフサイクル世代。start / stop / internalTeardown のたびにインクリメントし、
    // 古い session の event / frame を gating する (P1-4)。
    private val generation = AtomicInteger(0)

    @Volatile private var transport: TranscriptionTransport? = null
    @Volatile private var mic: MicCaptureSource? = null
    private var pumpJob: Job? = null
    private var eventJob: Job? = null
    private val bufferLock = Any()
    @Volatile private var ready = false
    private val preBuffer = ArrayDeque<String>()
    private val partialBuf = StringBuilder()

    /**
     * 同期的に Connecting に遷移し、副作用 (collect 開始 / transport.connect / mic.start)
     * は内部で scope.launch する。重複 start は idempotent。
     */
    fun start(config: TranscriptionConfig) {
        if (!config.isValid) {
            log.warn("transcription_start_invalid_config")
            _state.value = State.Error("api_key_missing")
            return
        }
        if (transport != null) return
        val myGen = generation.incrementAndGet()
        _state.value = State.Connecting
        synchronized(bufferLock) {
            preBuffer.clear()
            partialBuf.clear()
            ready = false
        }

        val newTransport = transportFactory(config)
        val newMic = micFactory(config, scope)
        transport = newTransport
        mic = newMic

        eventJob = scope.launch { newTransport.events.collect { onEvent(myGen, it) } }
        pumpJob = scope.launch { newMic.frames.collect { routeFrame(myGen, it) } }

        newTransport.connect()
        newMic.start()
        log.info("transcription_started", "generation" to myGen)
    }

    /** 同期的に Idle に遷移し teardown する。 */
    fun stop() {
        teardownToState(State.Idle, "stop_called")
    }

    /** scope ごと畳む。 */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun teardownToState(target: State, reason: String) {
        generation.incrementAndGet()
        pumpJob?.cancel(); pumpJob = null
        eventJob?.cancel(); eventJob = null
        runCatching { mic?.stop() }
        runCatching { transport?.close() }
        mic = null
        transport = null
        synchronized(bufferLock) {
            ready = false
            preBuffer.clear()
            partialBuf.clear()
        }
        _state.value = target
        log.info("transcription_torn_down", "reason" to reason, "generation" to generation.get())
    }

    private fun routeFrame(gen: Int, frame: ByteArray) {
        if (gen != generation.get()) return
        // P3-6: java.util.Base64.getEncoder() は RFC4648 (padded) を返す。OpenAI Realtime
        // API は padded base64 を受け入れる (実機検証済)。android.util.Base64 を使うと
        // JVM unit test で empty string が返るためデフォルトで避ける。
        val b64 = base64.encodeToString(frame)
        val sendNow = synchronized(bufferLock) {
            if (gen != generation.get()) return
            if (ready) true
            else {
                if (preBuffer.size >= MAX_PREBUFFER_CHUNKS) {
                    // P2-3: pre-buffer の目的は冒頭の発話を残すこと。古い方ではなく
                    // 新しい方を捨てる (= pollLast)。
                    preBuffer.pollLast()
                    log.warn("transcription_prebuffer_overflow_dropped_newest")
                }
                preBuffer.addLast(b64)
                false
            }
        }
        if (sendNow && gen == generation.get()) transport?.sendAudio(b64)
    }

    private fun onEvent(gen: Int, event: TranscriptionEvent) {
        if (gen != generation.get()) return
        when (event) {
            TranscriptionEvent.SessionReady -> flushPreBuffer(gen)
            is TranscriptionEvent.Delta -> {
                val current = synchronized(bufferLock) {
                    if (gen != generation.get()) return
                    partialBuf.append(event.text)
                    partialBuf.toString()
                }
                if (gen == generation.get()) _state.value = State.Listening(current)
            }
            is TranscriptionEvent.Completed -> {
                synchronized(bufferLock) { partialBuf.clear() }
                if (gen != generation.get()) return
                // P2-4: emit を scope.launch に切り出し、event collector の backpressure
                // (subscriber が遅い時) によって後続 Delta/Closed が遅延しないようにする。
                scope.launch { _finalized.emit(event.text) }
                _state.value = State.Listening("")
            }
            is TranscriptionEvent.Error -> {
                log.warn("transcription_error", "message" to event.message.take(256))
                // P3-7: 4KB の error blob を State に乗せて UI / ログを汚さない。
                teardownToState(State.Error(event.message.take(512)), "wire_error")
            }
            TranscriptionEvent.Closed -> {
                if (_state.value !is State.Error) {
                    teardownToState(State.Idle, "wire_closed")
                }
            }
        }
    }

    private fun flushPreBuffer(gen: Int) {
        if (gen != generation.get()) return
        val drained: List<String>
        synchronized(bufferLock) {
            if (gen != generation.get()) return
            drained = preBuffer.toList()
            preBuffer.clear()
            ready = true
        }
        if (drained.isNotEmpty()) {
            log.info("transcription_prebuffer_flushed", "chunks" to drained.size)
        }
        if (gen != generation.get()) return
        val t = transport ?: return
        drained.forEach { t.sendAudio(it) }
        // P1-4: gen が古ければ Listening への遷移を許可しない (stop → flush race)。
        if (gen == generation.get()) {
            _state.value = State.Listening(partialBuf.toString())
        }
    }

    private companion object {
        const val MAX_PREBUFFER_CHUNKS = 250
        private val base64: Base64.Encoder = Base64.getEncoder()
    }
}
