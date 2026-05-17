package com.example.claudemobilehud.phone.data.transcription

/**
 * Transcription セッションの実行時パラメータ (docs/03 §3.2.5.9)。
 * sampleRateHz / chunkMs / mono 前提の根拠は §3.2.5.9 を参照。
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

    /** PCM16 mono 前提: sampleRate * 2 (16bit) * chunkMs / 1000。stereo 化時は係数見直し。 */
    val frameBytes: Int get() = (sampleRateHz * 2 * chunkMs) / 1000
}
