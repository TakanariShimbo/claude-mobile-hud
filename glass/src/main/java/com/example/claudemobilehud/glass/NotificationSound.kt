package com.example.claudemobilehud.glass

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.claudemobilehud.glass.log.StructuredLog
import com.example.claudemobilehud.protocol.NotificationKind

/**
 * 通知音 (Phase 3 §FR-GL-70/71 / POC 踏襲)。
 *
 * - reply: chime 1 回
 * - permission: chime 2 回 (220ms 間隔)
 *
 * MediaPlayer は再生完了で release。複数連発時はインスタンスを別々に作る。
 */
object NotificationSound {
    private val log = StructuredLog("channel.glass.sound")
    private const val PERMISSION_INTERVAL_MS = 220L
    private val handler = Handler(Looper.getMainLooper())

    fun play(context: Context, kind: NotificationKind) {
        when (kind) {
            NotificationKind.REPLY -> playChime(context)
            NotificationKind.PERMISSION -> {
                playChime(context)
                handler.postDelayed({ playChime(context) }, PERMISSION_INTERVAL_MS)
            }
        }
    }

    private fun playChime(context: Context) {
        // P2-B of 5a review: MediaPlayer は start() 前に setAudioAttributes 等で throw した
        // 場合、completion listener が登録されないので release されず leak する。create 後は
        // try/catch で囲み、何が起きても release する経路を用意する。permission 通知 (2 連 / 220ms)
        // で繰り返されるため leak 累積が早い。
        val player = runCatching {
            MediaPlayer.create(context, R.raw.notification_chime)
        }.getOrNull() ?: run {
            log.warn("media_player_create_null")
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
            log.warn("chime_play_failed", t)
        }
    }
}
