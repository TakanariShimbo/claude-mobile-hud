package com.example.claudemobilehud.glass

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.claudemobilehud.glass.log.StructuredLog
import com.example.claudemobilehud.protocol.NotificationKind

/**
 * Glass app の全効果音を集約 (docs/03 §4.7)。5 種 Kind の契機 (§4.7.1)、context-less play
 * (§4.7.2)、MediaPlayer leak 防止 (§4.7.3 P2-B)、NotificationKind 変換 (§4.7.4) を参照。
 */
object SoundEffects {
    private val log = StructuredLog("channel.glass.sfx")
    private const val PERMISSION_INTERVAL_MS = 220L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null

    enum class Kind { INCOMING_REPLY, INCOMING_PERMISSION, SEND, RECORD_START, RECORD_STOP }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun play(kind: Kind) {
        val ctx = appContext ?: return
        play(ctx, kind)
    }

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
            // docs/03 §4.7.3: start() 前 throw も release を保証する経路。
            runCatching { player.release() }
            log.warn("sfx_play_failed", t, "kind" to kind.name.lowercase())
        }
    }
}
