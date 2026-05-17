package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
import com.example.claudemobilehud.phone.data.transcription.TranscriptionConfig
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.MicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 入力テキスト + 音声 transcription の facade (docs/03 §3.2.5)。session-per-draft 戦略は
 * §3.2.5.1、録音中 session 固定は §3.2.5.2、Glass mic 経路の `BtScoUnavailable` contract は
 * §3.2.5.6 を参照。
 */
class InputController(
    private val audioRouter: AudioRouter? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    transcription: TranscriptionClient? = null,
) {
    val transcription: TranscriptionClient = transcription ?: TranscriptionClient(scope = scope)

    /** Glass mic を BT SCO 経由で phone AudioRecord に流す seam (4c の AudioRouter が実装)。 */
    interface AudioRouter {
        fun routeToGlassMic(): Boolean
        fun restore()
    }

    private val log = StructuredLog("channel.input")

    private val _inputBySession = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _currentSessionId = MutableStateFlow<String?>(null)

    val text: StateFlow<String> = combine(_inputBySession, _currentSessionId) { map, id ->
        if (id == null) "" else map[id].orEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, "")

    private val _micSource = MutableStateFlow(MicSource.PHONE_FALLBACK)
    val micSource: StateFlow<MicSource> = _micSource.asStateFlow()

    private val _errors = MutableSharedFlow<PhoneWireError>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<PhoneWireError> = _errors.asSharedFlow()

    @Volatile private var transcriptBase = ""
    @Volatile private var transcriptionSessionId: String? = null
    @Volatile private var routedBt = false

    init {
        scope.launch {
            this@InputController.transcription.state.collect { st ->
                if (st is TranscriptionClient.State.Listening) {
                    val id = transcriptionSessionId ?: return@collect
                    writeSession(id, (transcriptBase + st.partial).trimStart())
                }
            }
        }
        scope.launch {
            this@InputController.transcription.finalized.collect { final ->
                val id = transcriptionSessionId ?: return@collect
                val merged = (transcriptBase + final).trimStart()
                transcriptBase = if (merged.isNotEmpty() && !merged.endsWith(" ")) "$merged " else merged
                writeSession(id, merged)
            }
        }
    }

    fun setCurrentSession(id: String?) {
        _currentSessionId.value = id
    }

    fun update(text: String) {
        val id = _currentSessionId.value
        if (id == null) {
            log.warn("input_update_skipped_no_current_session")
            return
        }
        writeSession(id, text)
        val st = transcription.state.value
        if (st is TranscriptionClient.State.Idle || st is TranscriptionClient.State.Error) {
            transcriptBase = text
        }
    }

    fun clear() {
        val id = _currentSessionId.value ?: return
        if (transcriptionSessionId != null) {
            stop()
        }
        _inputBySession.update { it - id }
        transcriptBase = ""
    }

    fun startWithPhoneMic(apiKey: String) = open(apiKey, routeToBt = false)

    fun startFromGlass(apiKey: String) = open(apiKey, routeToBt = true)

    /** docs/03 §3.2.5.2: transcriptionSessionId を null にしてから transcription.stop。 */
    fun stop() {
        transcriptionSessionId = null
        transcription.stop()
        if (routedBt) {
            audioRouter?.restore()
            routedBt = false
        }
        _micSource.value = MicSource.PHONE_FALLBACK
    }

    fun dispose() {
        stop()
        transcription.dispose()
        scope.cancel()
    }

    private fun open(apiKey: String, routeToBt: Boolean) {
        if (apiKey.isBlank()) {
            log.warn("transcription_start_skipped_empty_key")
            return
        }
        val id = _currentSessionId.value
        if (id == null) {
            log.warn("transcription_start_skipped_no_current_session")
            return
        }
        if (routeToBt) {
            // docs/03 §3.2.5.6: audioRouter == null も routeToGlassMic() == false も
            // 同じ BtScoUnavailable 経路に流す。
            val router = audioRouter
            val routed = router?.routeToGlassMic() ?: false
            routedBt = routed
            _micSource.value = if (routed) MicSource.GLASS else MicSource.PHONE_FALLBACK
            if (!routed) {
                log.warn(
                    "transcription_glass_route_unavailable",
                    "router_present" to (router != null),
                )
                _errors.tryEmit(PhoneWireError.Glass.BtScoUnavailable)
            }
        } else {
            _micSource.value = MicSource.PHONE_FALLBACK
            routedBt = false
        }
        transcriptionSessionId = id
        val existing = _inputBySession.value[id].orEmpty()
        transcriptBase = if (existing.isBlank()) "" else existing.trimEnd() + " "
        transcription.start(TranscriptionConfig(apiKey = apiKey))
    }

    private fun writeSession(id: String, value: String) {
        _inputBySession.update { it + (id to value) }
    }
}
