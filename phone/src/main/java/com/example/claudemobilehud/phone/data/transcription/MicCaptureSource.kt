package com.example.claudemobilehud.phone.data.transcription

import kotlinx.coroutines.flow.Flow

/**
 * Phase 3 §3.2.5 と独立した内部 seam。AudioRecord 由来の PCM チャンクを Flow に
 * 載せる小さな contract。
 *
 * - 本番実装は [MicCapture] (AudioRecord 24kHz mono PCM16)。
 * - test では fake が `frames` に意図的なバイト列を流して TranscriptionClient の
 *   pre-buffer / streaming flow を駆動する。
 */
internal interface MicCaptureSource {
    val frames: Flow<ByteArray>
    fun start()
    fun stop()
}
