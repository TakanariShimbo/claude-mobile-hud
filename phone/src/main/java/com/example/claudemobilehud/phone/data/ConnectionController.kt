package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Hub への SSE 接続を exp backoff で維持。Phase 3 §3.2.4。
 *
 * 状態:
 *   Idle  ←→ Connecting → Open
 *                |          |
 *                ↓          ↓
 *                Failed (再試行) ──→ AuthFailed (手動 reconnect 待ち)
 *
 * `update(Settings)` で baseUrl/token が変わったら今のループを止めて再起動。
 * `reconnect()` は AuthFailed リセット + attempt=0 + ループ再起動。
 */
class ConnectionController(
    parentContext: CoroutineContext = SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    private val clientFactory: (String, String) -> ChannelClient = { url, token ->
        ChannelClient(url, token)
    },
) {
    private val log = StructuredLog("mhud.conn")
    private val scope = CoroutineScope(parentContext)
    private val mutex = Mutex()

    private val _status = MutableStateFlow<ConnectivityState>(ConnectivityState.Idle)
    val status: StateFlow<ConnectivityState> = _status.asStateFlow()

    private val _events = MutableSharedFlow<SseEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SseEvent> = _events.asSharedFlow()

    private val _client = MutableStateFlow<ChannelClient?>(null)
    val client: StateFlow<ChannelClient?> = _client.asStateFlow()

    private val reconnectTrigger = Channel<Unit>(Channel.CONFLATED)

    private var loopJob: Job? = null
    private var lastSettings: Settings = Settings()

    /** Settings 変更時に呼ぶ。NotConfigured → Idle、それ以外は loop を再起動。 */
    suspend fun update(settings: Settings) = mutex.withLock {
        if (settings == lastSettings) return@withLock
        lastSettings = settings
        loopJob?.cancel()
        if (!settings.isConfigured) {
            _status.value = ConnectivityState.Idle
            _client.value = null
            return@withLock
        }
        val client = clientFactory(settings.baseUrl, settings.token)
        _client.value = client
        loopJob = scope.launch { runConnectionLoop(client) }
    }

    /** 手動再接続 (AuthFailed をリセット)。Settings 未設定なら no-op。 */
    fun reconnect() {
        reconnectTrigger.trySend(Unit)
    }

    /** scope を停止 (shutdown 時)。以後 update / reconnect は無効。 */
    fun close() {
        scope.cancel()
    }

    private suspend fun runConnectionLoop(client: ChannelClient) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _status.value = ConnectivityState.Connecting
            log.info("connect_attempt", "attempt" to attempt + 1)
            var lastError: String? = null
            var authFailed = false

            try {
                client.events().collect { event ->
                    when (event) {
                        SseEvent.Open -> {
                            attempt = 0
                            _status.value = ConnectivityState.Open
                        }
                        is SseEvent.Failure -> lastError = event.message
                        is SseEvent.AuthFailed -> authFailed = true
                        SseEvent.Closed -> { /* ループ次回試行 */ }
                        else -> { /* 中継のみ */ }
                    }
                    _events.emit(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e.message
                log.warn("connect_collect_threw", e)
            }

            if (authFailed) {
                _status.value = ConnectivityState.AuthFailed
                log.warn("auth_failed_waiting_manual_reconnect")
                reconnectTrigger.receive()
                attempt = 0
                continue
            }
            if (!currentCoroutineContext().isActive) return

            attempt++
            val delayMs = computeBackoffMs(attempt)
            _status.value = ConnectivityState.Failed(
                "${lastError ?: "disconnected"} / 再接続待ち #$attempt",
            )
            log.info("backoff", "attempt" to attempt, "delay_ms" to delayMs)
            delay(delayMs)
        }
    }

    companion object {
        /** 1s → 30s capped exp backoff + ±25% jitter (Phase 3 §3.2.4)。 */
        fun computeBackoffMs(attempt: Int, rng: Random = Random.Default): Long {
            val shift = (attempt - 1).coerceIn(0, 5)
            val baseMs = (1000L shl shift).coerceAtMost(30_000L)
            val jitterMs = (baseMs * rng.nextDouble(-0.25, 0.25)).toLong()
            return (baseMs + jitterMs).coerceAtLeast(100L)
        }
    }
}
