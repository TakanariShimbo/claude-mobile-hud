package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.data.model.SessionSummary
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phone-local の session / メッセージ集約。Phase 3 §3.2.3。
 *
 * - `messagesBySession`: session_id → 会話履歴 (Mutex 保護)
 * - 特殊な session_id `UNKNOWN_SESSION_ID` は「Phone send → reply 受信前」の暫定置き場
 *   (FR-PH-55 で確定 session_id に merge する)
 * - SSE event を入れて内部状態を更新するのは [applySseEvent]
 * - 公開状態は `snapshot: StateFlow<Snapshot>` で UI が観測する
 *
 * 永続化は [HistoryStore] に委譲。ここは on-memory state のみ。
 */
class SessionStore(private val historyStore: HistoryStore) {

    data class Snapshot(
        val sessions: List<SessionSummary>,
        val currentSessionId: String?,
        val messages: List<ChatMessage>,
    )

    private val log = StructuredLog("mhud.session")
    private val mutex = Mutex()
    private val messagesBySession: MutableMap<String, MutableList<ChatMessage>> = LinkedHashMap()
    private val activeSessionIds: MutableSet<String> = LinkedHashSet()
    private var currentSessionId: String? = null
    private var nextMessageId: Long = 1L

    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    suspend fun restoreFromHistory() = mutex.withLock {
        val loaded = historyStore.load()
        messagesBySession.clear()
        loaded.forEach { (id, list) -> messagesBySession[id] = list }
        // 既存履歴の最大 id を引き継ぐ
        nextMessageId = (loaded.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1L
        log.info(
            "history_restored",
            "sessions" to messagesBySession.size,
            "messages" to messagesBySession.values.sumOf { it.size },
            "next_id" to nextMessageId,
        )
        rebuildSnapshot()
    }

    suspend fun selectSession(sessionId: String) = mutex.withLock {
        if (currentSessionId == sessionId) return@withLock
        currentSessionId = sessionId
        log.info("session_selected", "session_id" to sessionId)
        rebuildSnapshot()
    }

    suspend fun deleteSession(sessionId: String) = mutex.withLock {
        if (messagesBySession.remove(sessionId) == null) return@withLock
        activeSessionIds.remove(sessionId)
        if (currentSessionId == sessionId) currentSessionId = null
        log.info("session_deleted", "session_id" to sessionId)
        rebuildSnapshot()
        persist()
    }

    suspend fun appendOutgoing(
        sessionId: String?,
        text: String,
        chatId: String?,
        image: ImageAttachment? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): ChatMessage = mutex.withLock {
        val bucket = sessionId ?: UNKNOWN_SESSION_ID
        val msg = ChatMessage(
            id = nextMessageId++,
            role = MessageRole.OUTGOING,
            text = text,
            chatId = chatId,
            sessionId = if (sessionId == UNKNOWN_SESSION_ID) null else sessionId,
            createdAtMs = timestampMs,
            image = image,
        )
        messagesBySession.getOrPut(bucket) { mutableListOf() }.add(msg)
        rebuildSnapshot()
        persist()
        msg
    }

    suspend fun appendIncoming(
        sessionId: String?,
        chatId: String?,
        text: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): ChatMessage = mutex.withLock {
        val bucket = sessionId ?: UNKNOWN_SESSION_ID
        val msg = ChatMessage(
            id = nextMessageId++,
            role = MessageRole.INCOMING,
            text = text,
            chatId = chatId,
            sessionId = if (sessionId == UNKNOWN_SESSION_ID) null else sessionId,
            createdAtMs = timestampMs,
        )
        messagesBySession.getOrPut(bucket) { mutableListOf() }.add(msg)
        rebuildSnapshot()
        persist()
        msg
    }

    /**
     * Phone send → reply 受信で session_id が確定したら、UNKNOWN_SESSION_ID にあった
     * 当該 chat_id を持つメッセージを正規 session に移す (FR-PH-55)。
     */
    suspend fun mergeUnknownSession(chatId: String, confirmedSessionId: String) = mutex.withLock {
        val unknown = messagesBySession[UNKNOWN_SESSION_ID] ?: return@withLock
        val (matching, remaining) = unknown.partition { it.chatId == chatId }
        if (matching.isEmpty()) return@withLock
        if (remaining.isEmpty()) {
            messagesBySession.remove(UNKNOWN_SESSION_ID)
        } else {
            messagesBySession[UNKNOWN_SESSION_ID] = remaining.toMutableList()
        }
        val target = messagesBySession.getOrPut(confirmedSessionId) { mutableListOf() }
        for (m in matching) target.add(m.copy(sessionId = confirmedSessionId))
        log.info(
            "unknown_session_merged",
            "chat_id" to chatId,
            "session_id" to confirmedSessionId,
            "moved" to matching.size,
        )
        rebuildSnapshot()
        persist()
    }

    /** Hub からの SSE event を内部状態に反映 (Phase 3 §3.2.1.3 の session 側だけ抜粋)。 */
    suspend fun applySseEvent(event: SseEvent) {
        when (event) {
            is SseEvent.Reply -> {
                // 先に UNKNOWN → confirmed の merge を済ませてから reply を append すると、
                // 「自分の送信 → reply」の時系列順が UI 上で保たれる。
                if (event.sessionId != null) {
                    mergeUnknownSession(event.chatId, event.sessionId)
                }
                appendIncoming(event.sessionId, event.chatId, event.text)
            }
            is SseEvent.SessionActive -> mutex.withLock {
                activeSessionIds.add(event.sessionId)
                rebuildSnapshot()
            }
            is SseEvent.SessionInactive -> mutex.withLock {
                activeSessionIds.remove(event.sessionId)
                rebuildSnapshot()
            }
            is SseEvent.SessionSnapshot -> reconcileActive(event.activeSessionIds)
            else -> Unit
        }
    }

    /** AD-13 / FR-PH-54: SSE 再接続 / 起動時の reconciliation。 */
    suspend fun reconcileActive(activeIds: List<String>) = mutex.withLock {
        activeSessionIds.clear()
        activeSessionIds.addAll(activeIds)
        // current が active から外れていてもユーザの選択は維持 (履歴はあるので)
        rebuildSnapshot()
    }

    /** 起動時 SettingsStore から復元した last_current_session_id を反映。 */
    suspend fun restoreCurrentSessionId(id: String?) = mutex.withLock {
        currentSessionId = id?.takeIf { it.isNotEmpty() }
        rebuildSnapshot()
    }

    /** shutdown 時に呼ぶ (debounce job を bypass して即 save)。Phase 3 §3.6.1。 */
    suspend fun flush() = mutex.withLock {
        persist()
    }

    private suspend fun persist() {
        historyStore.save(snapshotForPersistence())
    }

    private fun snapshotForPersistence(): Map<String, List<ChatMessage>> =
        messagesBySession.mapValues { it.value.toList() }

    private fun rebuildSnapshot() {
        val sessionIds = LinkedHashSet<String>().apply {
            addAll(activeSessionIds)
            addAll(messagesBySession.keys.filter { it != UNKNOWN_SESSION_ID })
        }
        val sessions = sessionIds.map { id ->
            SessionSummary(
                id = id,
                label = id.take(8), // FR-PH-?? で session ラベルが来たら差し替え
                messageCount = messagesBySession[id]?.size ?: 0,
                isActive = id in activeSessionIds,
            )
        }
        val current = currentSessionId
        val messages = current?.let { messagesBySession[it]?.toList() } ?: emptyList()
        _snapshot.value = Snapshot(
            sessions = sessions,
            currentSessionId = current,
            messages = messages,
        )
    }

    private fun emptySnapshot() = Snapshot(emptyList(), null, emptyList())

    companion object {
        /** 確定 session_id を受け取る前の暫定置き場。FR-PH-55。 */
        const val UNKNOWN_SESSION_ID: String = "__pending__"
    }
}
