package com.example.claudemobilehud.phone.data.transcription

import kotlinx.coroutines.flow.Flow

/**
 * TranscriptionWs の seam。テスト用 fake を差し替え可能にするための小さな contract。
 *
 * 実装は OpenAI Realtime API に WebSocket で繋ぐ [TranscriptionWs]。
 *
 * - `events` は SessionReady / Delta / Completed / Error / Closed を排出する。
 *   `Closed` が来た後の更なる emit は無いことを契約とする。
 * - `connect` は副作用付き。冪等 (連続呼び出しは 2 回目以降 no-op)。
 * - `sendAudio` は base64 文字列を 1 frame で送る。WS 未接続なら drop (例外を投げない)。
 * - `close` は冪等。
 */
internal interface TranscriptionTransport {
    val events: Flow<TranscriptionEvent>
    fun connect()
    fun sendAudio(base64: String)
    fun close()
}
