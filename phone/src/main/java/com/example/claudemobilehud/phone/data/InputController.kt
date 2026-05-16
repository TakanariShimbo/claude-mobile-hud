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
 * 入力テキストと音声 transcription を一括管理する。Phase 3 §3.2.5。
 *
 * - [text]: current session の入力欄テキスト (派生 StateFlow)。
 * - [micSource]: 録音時の音声入力経路。`GLASS` (BT SCO 経由) または `PHONE_FALLBACK`。
 * - [errors]: BT SCO ルーティング失敗等の transient エラー。Repository が UI に転送する。
 * - [transcription]: TranscriptionClient を公開 (UI / GlassRelay が直接購読する)。
 *
 * **session 毎の draft** ([inputBySession]):
 *   - reply auto-switch や手動 session 切替で旧 session の下書きを誤って新 session に
 *     持ち込まないよう session 単位で保持する。
 *   - current session の特定は [setCurrentSession] で Repository から伝えてもらう。
 *
 * **録音中の挙動**:
 *   - 開始時点の current session を `transcriptionSessionId` に固定。録音中の session
 *     切替は UI 構造上発生しない契約 (§3.2.5)。
 *   - [TranscriptionClient.state] が Listening になるたび `transcriptBase + partial` を
 *     当該 session の input に書く。
 *   - [TranscriptionClient.finalized] が来るたび確定 transcript を base に取り込む。
 *
 * **Glass mic 経路** (Phase 4c で完成):
 *   - [startFromGlass] は `audioRouter` を通じて BT SCO に切替えてから transcription を
 *     開始する。`audioRouter == null` の 4b2 段階、または `routeToGlassMic()` が false を
 *     返した場合は PHONE_FALLBACK にフォールバックし、`PhoneWireError.Glass.BtScoUnavailable`
 *     を [errors] に emit する (§3.7 contract、P1-3 fix)。
 */
class InputController(
    private val audioRouter: AudioRouter? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * TranscriptionClient を外部注入できるようにすることで、テストでは fake transport/mic
     * を持つインスタンスを差し替え可能。未指定時は controller の `scope` 上に新規構築。
     */
    transcription: TranscriptionClient? = null,
) {
    val transcription: TranscriptionClient = transcription ?: TranscriptionClient(scope = scope)

    /**
     * Glass mic を BT SCO 経由で phone の AudioRecord に流すルーティングを担当する seam。
     * 4c の AudioRouter (CXR-L + AudioManager) が実装する。
     */
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
            // P2-7: current session が無い間の入力は破棄するが silent ではなく log で警告。
            // UI 側は currentSessionId == null のとき入力欄を disable すべき。
            log.warn("input_update_skipped_no_current_session")
            return
        }
        writeSession(id, text)
        val st = transcription.state.value
        if (st is TranscriptionClient.State.Idle || st is TranscriptionClient.State.Error) {
            transcriptBase = text
        }
    }

    /**
     * Current session の draft を消す。送信成功 / 取消で呼ばれる。
     * 録音中なら transcription も停止する (P2-8)。
     */
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

    /**
     * 同期的に transcription を停止する。`Repository.send` から呼ばれる経路では
     * `transcriptionSessionId` を null にしてから transcription.stop() を呼ぶことで、
     * 直後の `clearInput()` と inflight な finalized event の race を抑止する (P2-1)。
     */
    fun stop() {
        transcriptionSessionId = null
        transcription.stop()
        if (routedBt) {
            audioRouter?.restore()
            routedBt = false
        }
        _micSource.value = MicSource.PHONE_FALLBACK
    }

    /** Application 終了時の片付け (テスト含む)。 */
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
            val router = audioRouter
            val routed = router?.routeToGlassMic() ?: false
            routedBt = routed
            _micSource.value = if (routed) MicSource.GLASS else MicSource.PHONE_FALLBACK
            if (!routed) {
                // P1-3: §3.7 BtScoUnavailable → UI Banner contract。router が null
                // (4b2 段階) でも、route 失敗 (4c 以降) でも同じ error を emit する。
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
