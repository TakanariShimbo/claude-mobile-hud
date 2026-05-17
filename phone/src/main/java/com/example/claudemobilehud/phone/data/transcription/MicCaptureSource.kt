package com.example.claudemobilehud.phone.data.transcription

import kotlinx.coroutines.flow.Flow

/**
 * AudioRecord 由来の PCM チャンクを Flow に載せる内部 seam (docs/03 §3.2.5)。本番実装は
 * [MicCapture]。test では fake が `frames` に意図的なバイト列を流して TranscriptionClient の
 * pre-buffer / streaming flow (§3.2.5.4) を駆動する。
 */
internal interface MicCaptureSource {
    val frames: Flow<ByteArray>
    fun start()
    fun stop()
}
