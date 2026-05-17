package com.example.claudemobilehud.phone.data.transcription

import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime API への WS クライアント (docs/03 §3.2.5.11)。URL / 認証 / `onOpen` で
 * session.update / pingInterval=15s と専用 OkHttpClient / 256-bounded DROP_LATEST /
 * `onFailure` の Error→Closed 2 連 emit / `close` で channel close は §3.2.5.11 を参照。
 */
internal class TranscriptionWs(
    private val config: TranscriptionConfig,
    private val httpClient: OkHttpClient = defaultClient(),
) : TranscriptionTransport {
    private val log = StructuredLog("channel.transcription")

    private val _events = Channel<TranscriptionEvent>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    override val events: Flow<TranscriptionEvent> = _events.receiveAsFlow()

    @Volatile
    private var ws: WebSocket? = null

    override fun connect() {
        if (ws != null) return
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        ws = httpClient.newWebSocket(req, listener)
    }

    override fun sendAudio(base64: String) {
        ws?.send(EventCodec.appendAudio(base64))
    }

    override fun close() {
        runCatching { ws?.close(1000, "client closing") }
        ws = null
        _events.close()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.info("ws_open", "http_code" to response.code)
            webSocket.send(EventCodec.sessionUpdate(config))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            EventCodec.decode(text)?.let { _events.trySend(it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log.info("ws_closing", "code" to code, "reason" to reason)
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.info("ws_closed", "code" to code, "reason" to reason)
            _events.trySend(TranscriptionEvent.Closed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = runCatching { response?.body?.string() }.getOrNull()
            val msg = "ws failure code=${response?.code} ${t.javaClass.simpleName}: ${t.message} body=$body"
            log.warn("ws_failure", "code" to (response?.code ?: -1), "ex" to t.javaClass.simpleName)
            _events.trySend(TranscriptionEvent.Error(msg))
            _events.trySend(TranscriptionEvent.Closed)
        }
    }

    companion object {
        /** docs/03 §3.2.5.11: WS 専用 (SSE 用 defaultClient と pingInterval 要件が違う)。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
