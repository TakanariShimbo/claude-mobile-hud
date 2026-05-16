package com.example.claudemobilehud.phone.data.transcription

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * AudioRecord 経由で 24kHz mono PCM16 を 1 frame=[TranscriptionConfig.chunkMs] ms 単位で
 * `frames` に流す。Phase 3 §3.2.5。
 *
 * - `VOICE_RECOGNITION` audio source を使い、Android 側で AEC / NS が薄く効くようにする。
 *   音楽 mic ではない (歌の transcription はターゲットではない)。
 * - 起動失敗パターン (state != INITIALIZED) は早期 return で log のみ。例外を投げない。
 *   呼び出し側 (TranscriptionClient) は frames が来ないことを timeout で検知する。
 * - 録音中は IO dispatcher に貼り付け、blocking read で chunk を吐く。`Job.cancel` で
 *   ループ脱出 → rec.stop + release。
 */
internal class MicCapture(
    private val config: TranscriptionConfig,
    private val scope: CoroutineScope,
) : MicCaptureSource {
    private val log = StructuredLog("channel.mic")

    // pre-buffer に積まれる前段なので SUSPEND は不要だが、起動直後の burst を取りこぼさないため
    // 64 frame (~2.5s @ 40ms) のバッファを確保。
    private val _frames = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<ByteArray> = _frames.asSharedFlow()

    // P2-6: silent drop は transcript 品質低下に直結するので、サンプリングして警告する。
    // 10 件目ごとに 1 行 log。実機 deployment 後の field 解析で見えるように。
    private var droppedFrames: Long = 0L

    private var job: Job? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        if (job != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            log.warn("mic_min_buf_invalid", "min_buf" to minBuf)
            return
        }
        val frameBytes = config.frameBytes
        val bufferBytes = maxOf(minBuf, frameBytes * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log.warn("mic_audio_record_uninit", "state" to rec.state)
            rec.release()
            return
        }
        record = rec
        rec.startRecording()

        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(frameBytes)
            try {
                while (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                        val emitted = _frames.tryEmit(chunk)
                        if (!emitted) {
                            droppedFrames++
                            if (droppedFrames % 10 == 0L) {
                                log.warn("mic_frame_dropped", "total_dropped" to droppedFrames)
                            }
                        }
                    } else if (n < 0) {
                        log.warn("mic_read_error", "rc" to n)
                        break
                    }
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
        log.info("mic_started", "sample_rate" to config.sampleRateHz, "chunk_ms" to config.chunkMs)
    }

    override fun stop() {
        runCatching { record?.stop() }
        job?.cancel()
        job = null
        record = null
        log.info("mic_stopped")
    }
}
