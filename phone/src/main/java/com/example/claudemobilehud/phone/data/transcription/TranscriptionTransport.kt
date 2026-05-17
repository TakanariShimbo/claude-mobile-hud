package com.example.claudemobilehud.phone.data.transcription

import kotlinx.coroutines.flow.Flow

/** docs/03 §3.2.5.14: TranscriptionWs の test seam。connect/close 冪等、Closed 後 emit 無し。 */
internal interface TranscriptionTransport {
    val events: Flow<TranscriptionEvent>
    fun connect()
    fun sendAudio(base64: String)
    fun close()
}
