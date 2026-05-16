package com.example.claudemobilehud.phone.data.transcription

/**
 * OpenAI Realtime API (transcription-only) からの wire 上イベントを正規化した形。
 *
 * - [SessionReady]: `session.update` の ack。これを受けて音声送信を本格開始。
 * - [Delta]: 中間 transcript (partial)。最終ではない。連結すると現在の talk-buffer が得られる。
 * - [Completed]: 文区切りでの確定 transcript。Phone UI には append される。
 * - [Error]: API 側エラー。Closed と組で流す。
 * - [Closed]: WebSocket 切断。Idle に戻る。
 *
 * `TranscriptionClient.state.Listening(partial)` の partial は Delta を累積した値。
 * Completed が来ると partial をクリアし、確定値を [TranscriptionClient.finalized] へ流す。
 */
sealed interface TranscriptionEvent {
    data object SessionReady : TranscriptionEvent
    data class Delta(val text: String) : TranscriptionEvent
    data class Completed(val text: String) : TranscriptionEvent
    data class Error(val message: String) : TranscriptionEvent
    data object Closed : TranscriptionEvent
}
