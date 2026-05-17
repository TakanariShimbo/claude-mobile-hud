package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.error.SharedWireError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Hub への SSE 接続を exp backoff で維持する (docs/03 §3.2.4)。HubAddress 単位の判定 (§3.2.4.1)、
 * cancelAndJoin 順序 (§3.2.4.2)、backoff 中の即起床 (§3.2.4.3)、AuthFailed 翻訳経路 (§3.2.4.4)、
 * buffer policy (§3.2.4.5)、backoff 計算式 (§3.2.4.6) を参照。
 */
class ConnectionController(
    parentContext: CoroutineContext = SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    private val clientFactory: (String, String) -> ChannelClient = { url, token ->
        ChannelClient(url, token)
    },
) {
    private val log = StructuredLog("channel.conn")
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

    /** AuthFailed 復帰 / Failed backoff 中断 のどちらにも使う共通 trigger。 */
    private val reconnectTrigger = Channel<Unit>(Channel.CONFLATED)

    private var loopJob: Job? = null
    private var lastAddress: HubAddress? = null

    suspend fun update(settings: Settings) = mutex.withLock {
        val newAddress = if (settings.isConfigured) {
            HubAddress(settings.baseUrl, settings.token)
        } else null
        if (newAddress == lastAddress) return@withLock
        lastAddress = newAddress
        // docs/03 §3.2.4.2: cancelAndJoin で旧 loop の完了を待ってから新 loop を起動。
        loopJob?.cancelAndJoin()
        if (newAddress == null) {
            _status.value = ConnectivityState.Idle
            _client.value = null
            return@withLock
        }
        val client = clientFactory(newAddress.baseUrl, newAddress.token)
        _client.value = client
        loopJob = scope.launch { runConnectionLoop(client) }
    }

    fun reconnect() {
        reconnectTrigger.trySend(Unit)
    }

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
                // docs/03 §3.2.4.4: newRequest の token 検査 throw を AuthFailed flag に翻訳。
                val wire = (e as? WireErrorException)?.wireError
                if (wire == SharedWireError.Connection.AuthFailed) authFailed = true
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
            // docs/03 §3.2.4.3: backoff delay 中の reconnect() で即起床。
            val woken = select<Boolean> {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                onTimeout(delayMs) { false }
                reconnectTrigger.onReceive { true }
            }
            if (woken) {
                attempt = 0
                log.info("backoff_interrupted_by_reconnect")
            }
        }
    }

    /** docs/03 §3.2.4.1: 再接続要否の判定単位。 */
    private data class HubAddress(val baseUrl: String, val token: String)

    companion object {
        /** docs/03 §3.2.4.6: 1s → 30s capped exp backoff + ±25% jitter。 */
        fun computeBackoffMs(attempt: Int, rng: Random = Random.Default): Long {
            val shift = (attempt - 1).coerceIn(0, 5)
            val baseMs = (1000L shl shift).coerceAtMost(30_000L)
            val jitterMs = (baseMs * rng.nextDouble(-0.25, 0.25)).toLong()
            return (baseMs + jitterMs).coerceAtLeast(100L)
        }
    }
}
