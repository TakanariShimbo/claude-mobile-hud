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
 * AudioRecord wrapper (docs/03 §3.2.5.12)。VOICE_RECOGNITION 選択、起動失敗の早期 return /
 * IO dispatcher blocking read / `_frames` buffer policy / drop frame sampling log (P2-6) は
 * §3.2.5.12 を参照。
 */
internal class MicCapture(
    private val config: TranscriptionConfig,
    private val scope: CoroutineScope,
) : MicCaptureSource {
    private val log = StructuredLog("channel.mic")

    private val _frames = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<ByteArray> = _frames.asSharedFlow()

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
                            // docs/03 §3.2.5.12 (P2-6): 10 件ごとにサンプリング warn。
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
