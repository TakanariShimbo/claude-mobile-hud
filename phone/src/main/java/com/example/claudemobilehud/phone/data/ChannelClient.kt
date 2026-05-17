package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.PermissionDecision
import com.example.claudemobilehud.protocol.error.SharedWireError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phone → Hub HTTP client。Phase 3 §3.2.2 / Phase 2 §4.3.1。
 *
 * - POST `/send`: text + 任意の image (base64)。chat_id を mint された応答を返す。
 * - POST `/permission`: verdict。`allow` / `deny`。410 で `PermissionGone`。
 * - GET `/events`: SSE。`SseEvent` を flow で配信。
 *
 * - HTTP 401 → `SharedWireError.Connection.AuthFailed`
 * - HTTP 410 → `SharedWireError.Permission.AlreadyVerdicted`
 * - HTTP 4xx 一般 → `SharedWireError.Connection.ServerError(code, bodyHead)`
 * - 4xx body の `error_code` が `image_too_large` → `PhoneWireError.Send.ImageTooLarge`
 * - 4xx body の `error_code` が `session_not_active` → `PhoneWireError.Send.SessionNotActive`
 */
open class ChannelClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val log = StructuredLog("channel.client")
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun send(
        text: String,
        sessionId: String?,
        image: ImageAttachment?,
        imageBase64: String?,
    ): Result<SendResponse> = coroutineRunCatching {
        val payload = SendRequest(
            text = text,
            sessionId = sessionId,
            imageBase64 = imageBase64,
            imageMime = image?.mime,
        )
        val body = json.encodeToString(SendRequest.serializer(), payload)
        val request = newRequest("send")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val raw = execute(request)
        json.decodeFromString(SendResponse.serializer(), raw)
    }

    suspend fun sendPermissionVerdict(
        requestId: String,
        decision: PermissionDecision,
    ): Result<Unit> = coroutineRunCatching {
        val payload = PermissionRequest(
            requestId = requestId,
            behavior = if (decision == PermissionDecision.ALLOW) "allow" else "deny",
        )
        val body = json.encodeToString(PermissionRequest.serializer(), payload)
        val request = newRequest("permission")
            .post(body.toRequestBody(jsonMedia))
            .build()
        execute(request)
    }

    /**
     * `callbackFlow` の既定 buffer は RENDEZVOUS で trySend が collector 待ちの間に
     * イベントを silent drop しうる。AD-13 の snapshot 連続 push (permission_snapshot
     * 直後の outstanding 群を順次再 push) を取りこぼさないため、UNLIMITED buffer を挟む。
     */
    open fun events(): Flow<SseEvent> = callbackFlow {
        val request = newRequest("events").get().build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(SseEvent.Open)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                val event = parseSseFrame(type, data) ?: return
                trySend(event)
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                val event = when {
                    response?.code == 401 -> SseEvent.AuthFailed
                    else -> SseEvent.Failure(
                        t?.message ?: response?.code?.toString() ?: "unknown",
                    )
                }
                trySend(event)
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(SseEvent.Closed)
                close()
            }
        }
        val source = EventSources.createFactory(httpClient).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
        .buffer(capacity = Channel.UNLIMITED, onBufferOverflow = BufferOverflow.SUSPEND)
        .flowOn(Dispatchers.IO)

    private fun parseSseFrame(type: String?, data: String): SseEvent? {
        return try {
            when (type) {
                "reply" -> {
                    val p = json.decodeFromString(ReplyDto.serializer(), data)
                    SseEvent.Reply(p.chatId, p.sessionId, p.text)
                }
                "permission" -> {
                    val p = json.decodeFromString(PermissionDto.serializer(), data)
                    SseEvent.Permission(
                        p.requestId, p.sessionId, p.toolName, p.description, p.inputPreview,
                    )
                }
                "permission_abort" -> {
                    val p = json.decodeFromString(PermissionAbortDto.serializer(), data)
                    SseEvent.PermissionAbort(p.requestId, p.reason)
                }
                "session_active" -> {
                    val p = json.decodeFromString(SessionIdDto.serializer(), data)
                    SseEvent.SessionActive(p.sessionId)
                }
                "session_inactive" -> {
                    val p = json.decodeFromString(SessionIdDto.serializer(), data)
                    SseEvent.SessionInactive(p.sessionId)
                }
                "session_snapshot" -> {
                    val p = json.decodeFromString(SessionSnapshotDto.serializer(), data)
                    SseEvent.SessionSnapshot(p.activeSessionIds)
                }
                "permission_snapshot" -> {
                    val p = json.decodeFromString(PermissionSnapshotDto.serializer(), data)
                    SseEvent.PermissionSnapshot(p.requestIds)
                }
                else -> {
                    log.warn("sse_unknown_event", "type" to (type ?: ""))
                    null
                }
            }
        } catch (e: Throwable) {
            log.warn("sse_decode_failed", e, "type" to (type ?: ""))
            null
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = httpClient.newCall(request).await()
        response.use { r ->
            val body = try {
                r.body.string()
            } catch (e: IOException) {
                ""
            }
            when {
                r.code == 401 -> throw SharedWireError.Connection.AuthFailed.asException()
                r.code == 410 -> throw SharedWireError.Permission.AlreadyVerdicted.asException()
                !r.isSuccessful -> throw mapErrorBody(r.code, body)
                else -> body
            }
        }
    }

    private fun mapErrorBody(httpCode: Int, body: String): Throwable {
        val parsed = runCatching {
            json.decodeFromString(ErrorBody.serializer(), body)
        }.getOrNull()
        val phone = when (parsed?.errorCode) {
            "image_too_large" -> PhoneWireError.Send.ImageTooLarge(-1L, -1L).asException()
            "session_not_active" -> PhoneWireError.Send.SessionNotActive("").asException()
            else -> null
        }
        return phone ?: SharedWireError.Connection.ServerError(
            httpCode = httpCode,
            bodyHead = body.take(200),
        ).asException()
    }

    private fun newRequest(path: String): Request.Builder {
        val url = baseUrl.trimEnd('/') + "/" + path
        val builder = Request.Builder().url(url)
        if (token.isNotEmpty()) {
            // OkHttp の Request.Builder.header は ASCII printable (0x20-0x7E) のみ受理。
            // non-ASCII / 制御文字を含む token は IllegalArgumentException で死んで通常の
            // Failed (再試行) 扱いになるため、ここで先に検出して AuthFailed に翻訳する。
            // ユーザが Settings で意図せず日本語等を貼り付けた場合に「再ペアダイアログ」
            // が表示される (= ConnectivityState.AuthFailed) UX 経路にのせる。
            for (c in token) {
                val code = c.code
                if (code < 0x20 || code > 0x7E) {
                    throw SharedWireError.Connection.AuthFailed.asException()
                }
            }
            builder.header("X-Token", token)
        }
        return builder
    }

    @Serializable
    private data class SendRequest(
        val text: String,
        @SerialName("session_id") val sessionId: String?,
        @SerialName("image_base64") val imageBase64: String?,
        @SerialName("image_mime") val imageMime: String?,
    )

    @Serializable
    data class SendResponse(
        @SerialName("chat_id") val chatId: String,
        @SerialName("session_id") val sessionId: String? = null,
    )

    @Serializable
    private data class PermissionRequest(
        @SerialName("request_id") val requestId: String,
        val behavior: String,
    )

    @Serializable
    private data class ErrorBody(
        @SerialName("error_code") val errorCode: String,
        val message: String = "",
    )

    @Serializable
    private data class ReplyDto(
        @SerialName("chat_id") val chatId: String,
        @SerialName("session_id") val sessionId: String? = null,
        val text: String,
    )

    @Serializable
    private data class PermissionDto(
        @SerialName("request_id") val requestId: String,
        @SerialName("session_id") val sessionId: String? = null,
        @SerialName("tool_name") val toolName: String,
        val description: String,
        @SerialName("input_preview") val inputPreview: String,
    )

    @Serializable
    private data class PermissionAbortDto(
        @SerialName("request_id") val requestId: String,
        val reason: String? = null,
    )

    @Serializable
    private data class SessionIdDto(@SerialName("session_id") val sessionId: String)

    @Serializable
    private data class SessionSnapshotDto(
        @SerialName("active_session_ids") val activeSessionIds: List<String>,
    )

    @Serializable
    private data class PermissionSnapshotDto(
        @SerialName("request_ids") val requestIds: List<String>,
    )

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 用に無制限
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

// --- 例外化ユーティリティ (Logger 経由で wrap) ---

class WireErrorException(val wireError: Any) : RuntimeException(messageOf(wireError))

private fun messageOf(err: Any): String = when (err) {
    is SharedWireError -> err.message
    is PhoneWireError.Send.ImageTooLarge -> "image too large"
    is PhoneWireError.Send.SessionNotActive -> "session not active: ${err.sessionId}"
    is PhoneWireError.Send.Cancelled -> "send cancelled"
    is PhoneWireError -> err.toString()
    else -> err.toString()
}

fun SharedWireError.asException(): Throwable = WireErrorException(this)
fun PhoneWireError.asException(): Throwable = WireErrorException(this)

// --- OkHttp Call.await: callback → suspend ---
// `suspendCancellableCoroutine` で coroutine cancel を `Call.cancel()` に伝播。
// 結果として scope cancel 時に socket がリークしない。
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    })
    cont.invokeOnCancellation { runCatching { this@await.cancel() } }
}

/**
 * `kotlin.runCatching` は `CancellationException` も catch する罠があるので、
 * suspending block の中で使う場合は専用版を介して cancellation を rethrow する。
 * Kotlin/kotlinx.coroutines#1814 で長らく議論されている既知の pitfall。
 */
private inline fun <T> coroutineRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}
