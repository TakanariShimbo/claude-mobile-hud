package com.example.claudemobilehud.phone.data.transcription

/**
 * Transcription セッションの実行時パラメータ。
 *
 * - [apiKey]: OpenAI Bearer token。Settings から渡される。空文字は invalid。
 * - [transcriptionModel]: API 側 `session.audio.input.transcription.model`。
 *   現状は `gpt-realtime-whisper` (= realtime API の transcription pipeline)。
 * - [sampleRateHz]: 24kHz mono PCM16 を運用上の最適点として固定。Realtime API の
 *   想定 sample rate と一致。
 * - [chunkMs]: WS 1 frame あたりの送出長。40ms = 1920 bytes / chunk。長すぎると
 *   delta latency、短すぎると WS overhead 増。POC で実機検証済みの値。
 */
data class TranscriptionConfig(
    val apiKey: String,
    val transcriptionModel: String = "gpt-realtime-whisper",
    val sampleRateHz: Int = 24_000,
    val chunkMs: Int = 40,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(chunkMs > 0) { "chunkMs must be positive: $chunkMs" }
        require(frameBytes > 0) { "frameBytes derived = 0 (sampleRateHz*chunkMs too small)" }
    }

    val isValid: Boolean get() = apiKey.isNotBlank()

    /**
     * 1 frame の PCM16 bytes = sampleRate * 2 (16bit) * chunkMs / 1000。
     * **channels=1 (mono) を前提**。stereo に変更する場合は `* 2` の係数を見直すこと
     * (MicCapture も同じ前提で AudioFormat.CHANNEL_IN_MONO を使う)。
     */
    val frameBytes: Int get() = (sampleRateHz * 2 * chunkMs) / 1000
}
