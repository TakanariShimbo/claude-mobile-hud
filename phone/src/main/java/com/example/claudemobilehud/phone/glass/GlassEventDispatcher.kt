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
 * Glass からの wire event を Repository action に変換する。Phase 3 §3.4.2。
 *
 * - Hello: 再接続時の初期同期トリガ。Relay の `refresh()` を叩いて現在 state を再 push。
 * - SelectSession: session 切替。
 * - GestureEvent: tap → start/stop transcription、swipe_forward → send、swipe_back → clear。
 * - ListeningCancel: transcription 停止 + input クリア。
 * - PermissionVerdictEvent: Hub に POST /permission を中継。
 *
 * 設計上 Phone → Glass の event が Glass → Phone 経由で戻ってくることは無いので、
 * その他の `WireEvent` 派生は warn log で drop する。
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
                // P3-7: heartbeat ping は Phone→Glass→Phone の echo になる可能性があり、
                // warn を出さずに silently 受け流す。
            }
            else -> {
                // Phone → Glass で送出するイベント (CurrentState 等) や CXR 内部 event は
                // Glass 側で生成して送り返してこないはず。observability のため warn 残す。
                log.warn("glass_unhandled_wire_event", "type" to event::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun handleGesture(which: GestureKind) {
        when (which) {
            GestureKind.TAP -> toggleTranscription()
            GestureKind.SWIPE_FORWARD -> {
                // P1-4: inputText.value を launch 内で読むと、前回 send の clearInput()
                // 完了タイミングによっては空文字を取りうる。ここで同期的に snapshot を取る。
                // また handle() は collect の中で suspend で動いており、2 件目の
                // SWIPE_FORWARD は前の send() suspend が return してから初めて評価
                // されるため、二重送信は構造的に発生しない。
                val snapshot = repository.inputText.value
                repository.send(snapshot)
            }
            GestureKind.SWIPE_BACK -> repository.clearInput()
            GestureKind.DOUBLE_TAP -> { /* Glass 側だけ session 選択画面に戻す。Phone 側は無処理。 */ }
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
