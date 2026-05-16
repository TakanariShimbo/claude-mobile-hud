package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.data.model.SessionSummary
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * 永続化は [HistoryStore] に委譲。各変更操作の終わりに **500ms debounce** で save を schedule
 * (P2-4: send/reply 毎の全書き換えを防ぐ)。[flush] で即時 save する (shutdown 用)。
 */
class SessionStore(
    private val historyStore: HistoryStore,
    private val saveDebounceMs: Long = 500L,
    persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    data class Snapshot(
        val sessions: List<SessionSummary>,
        val currentSessionId: String?,
        val messages: List<ChatMessage>,
    )

    private val log = StructuredLog("channel.session")
    private val mutex = Mutex()
    private val messagesBySession: MutableMap<String, MutableList<ChatMessage>> = LinkedHashMap()
    private val activeSessionIds: MutableSet<String> = LinkedHashSet()
    private var currentSessionId: String? = null
    private var nextMessageId: Long = 1L

    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val persistScope = persistScope
    private val persistMutex = Mutex()
    private var saveJob: Job? = null

    suspend fun restoreFromHistory() = mutex.withLock {
        val loaded = historyStore.load()
        messagesBySession.clear()
        loaded.forEach { (id, list) -> messagesBySession[id] = list }
        nextMessageId = (loaded.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1L
        log.info(
            "history_restored",
            "sessions" to messagesBySession.size,
            "messages" to messagesBySession.values.sumOf { it.size },
            "next_id" to nextMessageId,
        )
        rebuildSnapshot()
    }

    suspend fun selectSession(sessionId: String) {
        val changed = mutex.withLock {
            if (currentSessionId == sessionId) false
            else {
                currentSessionId = sessionId
                rebuildSnapshot()
                true
            }
        }
        if (changed) log.info("session_selected", "session_id" to sessionId)
    }

    suspend fun deleteSession(sessionId: String) {
        val removed = mutex.withLock {
            if (messagesBySession.remove(sessionId) == null) false
            else {
                activeSessionIds.remove(sessionId)
                if (currentSessionId == sessionId) currentSessionId = null
                rebuildSnapshot()
                true
            }
        }
        if (removed) {
            log.info("session_deleted", "session_id" to sessionId)
            schedulePersist()
        }
    }

    suspend fun appendOutgoing(
        sessionId: String?,
        text: String,
        chatId: String?,
        image: ImageAttachment? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): ChatMessage {
        val msg = mutex.withLock {
            val m = appendLocked(MessageRole.OUTGOING, sessionId, chatId, text, image, timestampMs)
            rebuildSnapshot()
            m
        }
        schedulePersist()
        return msg
    }

    suspend fun appendIncoming(
        sessionId: String?,
        chatId: String?,
        text: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): ChatMessage {
        val msg = mutex.withLock {
            val m = appendLocked(MessageRole.INCOMING, sessionId, chatId, text, null, timestampMs)
            rebuildSnapshot()
            m
        }
        schedulePersist()
        return msg
    }

    /**
     * Hub mint された chat_id を pending outgoing メッセージに後付け (FR-PH-55)。
     * @return 紐づけに成功した ChatMessage (見つからなければ null)
     */
    suspend fun assignChatIdToPending(
        localMessageId: Long,
        chatId: String,
        confirmedSessionId: String?,
    ): ChatMessage? {
        val updated = mutex.withLock {
            val u = assignChatIdLocked(localMessageId, chatId, confirmedSessionId)
            if (u != null) rebuildSnapshot()
            u
        }
        if (updated != null) schedulePersist()
        return updated
    }

    /** 送信失敗時に local echo を取り消す (Repository.handleSendFailure 経由)。 */
    suspend fun removePendingMessage(localMessageId: Long): Boolean {
        val removed = mutex.withLock {
            val ok = removePendingLocked(localMessageId)
            if (ok) rebuildSnapshot()
            ok
        }
        if (removed) schedulePersist()
        return removed
    }

    /**
     * Phone send → reply 受信で session_id が確定したら、UNKNOWN_SESSION_ID にあった
     * 当該 chat_id を持つメッセージを正規 session に移す (FR-PH-55)。
     */
    suspend fun mergeUnknownSession(chatId: String, confirmedSessionId: String) {
        val moved = mutex.withLock {
            val n = mergeUnknownSessionLocked(chatId, confirmedSessionId)
            if (n > 0) rebuildSnapshot()
            n
        }
        if (moved > 0) {
            log.info(
                "unknown_session_merged",
                "chat_id" to chatId,
                "session_id" to confirmedSessionId,
                "moved" to moved,
            )
            schedulePersist()
        }
    }

    /**
     * Hub からの SSE event を内部状態に反映。Reply は 1 つの mutex 配下で
     * merge → append → rebuild → persist 1 回、を実現する (P2-1)。
     */
    suspend fun applySseEvent(event: SseEvent) {
        when (event) {
            is SseEvent.Reply -> applyReply(event)
            is SseEvent.SessionActive -> {
                mutex.withLock {
                    activeSessionIds.add(event.sessionId)
                    rebuildSnapshot()
                }
            }
            is SseEvent.SessionInactive -> {
                mutex.withLock {
                    activeSessionIds.remove(event.sessionId)
                    rebuildSnapshot()
                }
            }
            is SseEvent.SessionSnapshot -> reconcileActive(event.activeSessionIds)
            else -> Unit
        }
    }

    /** AD-13 / FR-PH-54: SSE 再接続 / 起動時の reconciliation。 */
    suspend fun reconcileActive(activeIds: List<String>) {
        mutex.withLock {
            activeSessionIds.clear()
            activeSessionIds.addAll(activeIds)
            rebuildSnapshot()
        }
    }

    /** 起動時 SettingsStore から復元した last_current_session_id を反映。 */
    suspend fun restoreCurrentSessionId(id: String?) {
        mutex.withLock {
            currentSessionId = id?.takeIf { it.isNotEmpty() }
            rebuildSnapshot()
        }
    }

    /**
     * shutdown 時に呼ぶ (debounce job を bypass して即時 save)。Phase 3 §3.6.1。
     * 進行中の save と debounce 待ちの両方を保証する。
     */
    suspend fun flush() {
        // pending debounce job を取り消して即時 save に倒す
        saveJob?.cancelAndJoin()
        saveJob = null
        persistMutex.withLock {
            historyStore.save(snapshotForPersistence())
        }
    }

    // --- private: lock-aware helpers ---

    private suspend fun applyReply(event: SseEvent.Reply) {
        // 1 つの mutex 配下で merge → append → rebuild、persist は 1 回だけ schedule (P2-1)
        val touched = mutex.withLock {
            var changed = false
            if (event.sessionId != null) {
                val moved = mergeUnknownSessionLocked(event.chatId, event.sessionId)
                if (moved > 0) changed = true
            }
            appendLocked(
                role = MessageRole.INCOMING,
                sessionId = event.sessionId,
                chatId = event.chatId,
                text = event.text,
                image = null,
                timestampMs = System.currentTimeMillis(),
            )
            rebuildSnapshot()
            true.also { _ -> changed }
        }
        if (touched) schedulePersist()
    }

    private fun appendLocked(
        role: MessageRole,
        sessionId: String?,
        chatId: String?,
        text: String,
        image: ImageAttachment?,
        timestampMs: Long,
    ): ChatMessage {
        val bucket = sessionId ?: UNKNOWN_SESSION_ID
        val msg = ChatMessage(
            id = nextMessageId++,
            role = role,
            text = text,
            chatId = chatId,
            sessionId = if (sessionId == UNKNOWN_SESSION_ID) null else sessionId,
            createdAtMs = timestampMs,
            image = image,
        )
        messagesBySession.getOrPut(bucket) { mutableListOf() }.add(msg)
        return msg
    }

    private fun assignChatIdLocked(
        localMessageId: Long,
        chatId: String,
        confirmedSessionId: String?,
    ): ChatMessage? {
        for ((bucket, list) in messagesBySession) {
            val idx = list.indexOfFirst { it.id == localMessageId }
            if (idx < 0) continue
            val old = list[idx]
            val patched = old.copy(
                chatId = chatId,
                sessionId = confirmedSessionId ?: old.sessionId,
            )
            list[idx] = patched
            if (confirmedSessionId != null &&
                bucket == UNKNOWN_SESSION_ID &&
                confirmedSessionId != UNKNOWN_SESSION_ID
            ) {
                list.removeAt(idx)
                if (list.isEmpty()) messagesBySession.remove(UNKNOWN_SESSION_ID)
                messagesBySession.getOrPut(confirmedSessionId) { mutableListOf() }.add(patched)
            }
            return patched
        }
        return null
    }

    private fun removePendingLocked(localMessageId: Long): Boolean {
        val iterator = messagesBySession.entries.iterator()
        while (iterator.hasNext()) {
            val (_, list) = iterator.next()
            val idx = list.indexOfFirst { it.id == localMessageId }
            if (idx < 0) continue
            list.removeAt(idx)
            if (list.isEmpty()) iterator.remove()
            return true
        }
        return false
    }

    private fun mergeUnknownSessionLocked(chatId: String, confirmedSessionId: String): Int {
        val unknown = messagesBySession[UNKNOWN_SESSION_ID] ?: return 0
        val (matching, remaining) = unknown.partition { it.chatId == chatId }
        if (matching.isEmpty()) return 0
        if (remaining.isEmpty()) {
            messagesBySession.remove(UNKNOWN_SESSION_ID)
        } else {
            messagesBySession[UNKNOWN_SESSION_ID] = remaining.toMutableList()
        }
        val target = messagesBySession.getOrPut(confirmedSessionId) { mutableListOf() }
        for (m in matching) target.add(m.copy(sessionId = confirmedSessionId))
        return matching.size
    }

    /**
     * 500ms 後に save を実行。連続呼び出しは前の job を cancel して新たに schedule (debounce)。
     * P2-4 / Phase 3 §3.6.1。
     */
    private fun schedulePersist() {
        saveJob?.cancel()
        saveJob = persistScope.launch {
            delay(saveDebounceMs)
            // 直前の save と競合しないよう persistMutex で直列化
            persistMutex.withLock {
                val data = mutex.withLock { snapshotForPersistence() }
                historyStore.save(data)
            }
        }
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
                label = id.take(8),
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
        /**
         * 確定 session_id を受け取る前の暫定置き場。FR-PH-55。
         * NUL を含むため Hub からの実 session_id (UUID v4) と衝突しない (P3-4)。
         */
        const val UNKNOWN_SESSION_ID: String = " __pending__"
    }
}
