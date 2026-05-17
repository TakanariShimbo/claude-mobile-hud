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
 * OpenAI Realtime API (transcription-only) への WebSocket クライアント。
 * 内部 seam として [TranscriptionTransport] を満たす。
 *
 * docs/03 §3.2.5 (SessionReady 後に pre-buffer flush は §3.2.5.4)。設計判断:
 *   - 接続成功時に `session.update` を 1 回送り、ack (= SessionReady) を待ってから
 *     呼び出し側 (TranscriptionClient) が音声送信を開始する。
 *   - `pingInterval=15s`: OpenAI 側の idle timeout (~60s) より十分短く設定して
 *     keep-alive。
 *   - `readTimeout=0`: 長時間 idle (発話間の沈黙) を WS が殺さないように 0 で無制限。
 *   - `onFailure` 時は Error → Closed の順で emit して、client 側が teardown する。
 */
internal class TranscriptionWs(
    private val config: TranscriptionConfig,
    // P3-10: 単一 OkHttpClient 共有のため、Repository 経由で渡せる builder。
    // 未指定時はインスタンスごとに新規に建てる (compat path)。
    private val httpClient: OkHttpClient = defaultClient(),
) : TranscriptionTransport {
    private val log = StructuredLog("channel.transcription")

    // P2-5: 256 件 bounded + DROP_LATEST。malicious server が message 連打しても
    // 無限 buffer に積まれない。通常運用では 256 件超は起こらない (delta/completed の頻度より)。
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
        // P2-5: Channel を閉じてリーク防止。collector 側は cancellation で処理される。
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
        /** P3-10: WS 用 OkHttp client は SSE 用 defaultClient とは分離。pingInterval が要件異なる。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
