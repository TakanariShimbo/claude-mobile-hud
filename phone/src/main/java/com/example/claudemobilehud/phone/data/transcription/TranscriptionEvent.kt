package com.example.claudemobilehud.phone.data.transcription

/** docs/03 §3.2.5.13: OpenAI Realtime API event を正規化した sealed。Delta = partial / Completed = 確定。 */
sealed interface TranscriptionEvent {
    data object SessionReady : TranscriptionEvent
    data class Delta(val text: String) : TranscriptionEvent
    data class Completed(val text: String) : TranscriptionEvent
    data class Error(val message: String) : TranscriptionEvent
    data object Closed : TranscriptionEvent
}
