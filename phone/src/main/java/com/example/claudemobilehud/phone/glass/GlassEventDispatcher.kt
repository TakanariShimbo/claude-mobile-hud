package com.example.claudemobilehud.phone.glass

import com.example.claudemobilehud.phone.data.ChannelRepository
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.phone.service.GlassConnectionService
import com.example.claudemobilehud.protocol.GestureEvent
import com.example.claudemobilehud.protocol.GestureKind
import com.example.claudemobilehud.protocol.Hello
import com.example.claudemobilehud.protocol.ListeningCancel
import com.example.claudemobilehud.protocol.PermissionVerdictEvent
import com.example.claudemobilehud.protocol.Ping
import com.example.claudemobilehud.protocol.SelectSession
import com.example.claudemobilehud.protocol.WireEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Glass からの wire event を Repository action に変換する (docs/03 §3.4.2)。
 * gesture handling の race-free 設計 (sessionId snapshot / wasListening / inputText snapshot)
 * は §3.4.2.1 を参照。
 */
class GlassEventDispatcher(
    private val repository: ChannelRepository,
    private val relay: GlassRelay,
    private val parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val log = StructuredLog("channel.glass.dispatcher")
    private val scope = CoroutineScope(SupervisorJob() + parentScope.coroutineContext)
    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            GlassConnectionService.events.collect { event ->
                handle(event)
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    private suspend fun handle(event: WireEvent) {
        when (event) {
            is Hello -> {
                log.info("glass_hello")
                relay.refresh()
            }
            is SelectSession -> {
                log.info("glass_select_session", "id" to event.id)
                repository.selectSession(event.id)
            }
            is GestureEvent -> {
                log.info("glass_gesture", "which" to event.which.name)
                handleGesture(event.which)
            }
            is ListeningCancel -> {
                log.info("glass_listening_cancel")
                repository.stopTranscription()
                repository.clearInput()
            }
            is PermissionVerdictEvent -> {
                log.info(
                    "glass_permission_verdict",
                    "request_id" to event.requestId,
                    "decision" to event.decision.name,
                )
                repository.respondPermission(event.requestId, event.decision)
            }
            is Ping -> {
                // P3-7: Phone→Glass→Phone echo 経路があり得るので silently 受け流す。
            }
            else -> {
                log.warn("glass_unhandled_wire_event", "type" to event::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun handleGesture(which: GestureKind) {
        // docs/03 §3.4.2.1 P2-A: gesture 受信時点の current session を snapshot。
        val sessionIdAtGesture = repository.uiState.value.currentSessionId
        when (which) {
            GestureKind.TAP -> {
                // docs/03 §3.4.2.1: wasListening は toggleTranscription 前に評価する。
                val wasListening = repository.input.transcription.state.value.let {
                    it is TranscriptionClient.State.Listening ||
                        it is TranscriptionClient.State.Connecting
                }
                toggleTranscription()
                if (wasListening) repository.setConfirming(sessionIdAtGesture, true)
            }
            GestureKind.SWIPE_FORWARD -> {
                // docs/03 §3.4.2.1: inputText は launch 前に snapshot。送信失敗で CONFIRMING に
                // 戻したい場合に備え confirming flag は送信成功側でも畳む (両側 idempotent)。
                repository.setConfirming(sessionIdAtGesture, false)
                val snapshot = repository.inputText.value
                repository.send(snapshot)
            }
            GestureKind.SWIPE_BACK -> {
                repository.setConfirming(sessionIdAtGesture, false)
                repository.clearInput()
            }
            GestureKind.DOUBLE_TAP -> { /* Glass 側で session 選択画面に戻す。Phone は無処理。 */ }
        }
    }

    private fun toggleTranscription() {
        val st = repository.input.transcription.state.value
        if (st is TranscriptionClient.State.Idle || st is TranscriptionClient.State.Error) {
            repository.startTranscriptionFromGlass()
        } else {
            repository.stopTranscription()
        }
    }
}
