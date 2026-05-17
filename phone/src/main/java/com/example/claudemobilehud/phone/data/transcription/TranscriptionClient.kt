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
 * `MicCapture` + `TranscriptionWs` を束ねた transcription facade。docs/03 §3.2.5.3
 * (ライフサイクル / 同期 start-stop / generation gating)、§3.2.5.4 (pre-buffer)、
 * §3.2.5.5 (Base64)、§3.2.5.7 (backpressure)、§3.2.5.8 (error blob 切り詰め) を参照。
 *
 * テスト seam: `transportFactory` / `micFactory` で fake transport / mic を注入可能。
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

    private val _finalized = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val finalized: SharedFlow<String> = _finalized.asSharedFlow()

    private val generation = AtomicInteger(0)

    @Volatile private var transport: TranscriptionTransport? = null
    @Volatile private var mic: MicCaptureSource? = null
    private var pumpJob: Job? = null
    private var eventJob: Job? = null
    private val bufferLock = Any()
    @Volatile private var ready = false
    private val preBuffer = ArrayDeque<String>()
    private val partialBuf = StringBuilder()

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

    fun stop() {
        teardownToState(State.Idle, "stop_called")
    }

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
        val b64 = base64.encodeToString(frame)
        val sendNow = synchronized(bufferLock) {
            if (gen != generation.get()) return
            if (ready) true
            else {
                if (preBuffer.size >= MAX_PREBUFFER_CHUNKS) {
                    // docs/03 §3.2.5.4: 新しい方を捨てる (冒頭の発話を残す)。
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
                // docs/03 §3.2.5.7: subscriber が遅くても後続 event を遅延させないため launch で切り出す。
                scope.launch { _finalized.emit(event.text) }
                _state.value = State.Listening("")
            }
            is TranscriptionEvent.Error -> {
                log.warn("transcription_error", "message" to event.message.take(256))
                // docs/03 §3.2.5.8: 4KB error blob を 512 文字に切り詰める。
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
        if (gen == generation.get()) {
            _state.value = State.Listening(partialBuf.toString())
        }
    }

    private companion object {
        const val MAX_PREBUFFER_CHUNKS = 250
        private val base64: Base64.Encoder = Base64.getEncoder()
    }
}
