package com.example.claudemobilehud.glass

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.claudemobilehud.glass.log.StructuredLog
import com.example.claudemobilehud.protocol.NotificationKind

/**
 * Glass app の全効果音を 1 箇所に集約 (旧 NotificationSound + UI feedback)。
 *
 * 鳴らす契機:
 *   - INCOMING_REPLY:       Phone からの reply 通知 → chime 1 回
 *   - INCOMING_PERMISSION:  Phone からの permission 通知 → chime 2 連 (220ms 間隔; FR-GL-71)
 *   - SEND:                 OUTGOING message の最大 id が増加 (MessagesEvent)
 *   - RECORD_START:         CurrentState.transcriptState が `→ LISTENING`
 *   - RECORD_STOP:          CurrentState.transcriptState が `LISTENING →`
 *
 * 検出は MainActivity 側の collect で行い、ここは単に再生だけ担当。
 *
 * MediaPlayer は再生完了で release。複数連発時はインスタンスを別々に作る。
 * P2-B of 5a review: MediaPlayer は start() 前に setAudioAttributes 等で throw した
 * 場合、completion listener が登録されないので release されず leak する。create 後は
 * try/catch で囲み、何が起きても release する経路を用意する。
 */
object SoundEffects {
    private val log = StructuredLog("channel.glass.sfx")
    private const val PERMISSION_INTERVAL_MS = 220L
    private val handler = Handler(Looper.getMainLooper())

    enum class Kind { INCOMING_REPLY, INCOMING_PERMISSION, SEND, RECORD_START, RECORD_STOP }

    fun play(context: Context, kind: Kind) {
        when (kind) {
            Kind.INCOMING_REPLY -> playOnce(context, R.raw.notification_chime, kind)
            Kind.INCOMING_PERMISSION -> {
                playOnce(context, R.raw.notification_chime, kind)
                handler.postDelayed(
                    { playOnce(context, R.raw.notification_chime, kind) },
                    PERMISSION_INTERVAL_MS,
                )
            }
            Kind.SEND -> playOnce(context, R.raw.sound_send, kind)
            Kind.RECORD_START -> playOnce(context, R.raw.sound_record_start, kind)
            Kind.RECORD_STOP -> playOnce(context, R.raw.sound_record_stop, kind)
        }
    }

    /** NotificationKind → Kind 変換 (incoming 通知 collect 経路の glue)。 */
    fun play(context: Context, kind: NotificationKind) {
        val mapped = when (kind) {
            NotificationKind.REPLY -> Kind.INCOMING_REPLY
            NotificationKind.PERMISSION -> Kind.INCOMING_PERMISSION
        }
        play(context, mapped)
    }

    private fun playOnce(context: Context, resId: Int, kind: Kind) {
        val player = runCatching { MediaPlayer.create(context, resId) }.getOrNull() ?: run {
            log.warn("media_player_create_null", "kind" to kind.name.lowercase())
            return
        }
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setOnCompletionListener { runCatching { it.release() } }
            player.setOnErrorListener { mp, _, _ ->
                runCatching { mp.release() }
                true
            }
            player.start()
        } catch (t: Throwable) {
            runCatching { player.release() }
            log.warn("sfx_play_failed", t, "kind" to kind.name.lowercase())
        }
    }
}
