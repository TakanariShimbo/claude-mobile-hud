package com.example.claudemobilehud.phone.glass

import android.content.Context
import android.media.AudioManager
import com.example.claudemobilehud.phone.data.InputController
import com.example.claudemobilehud.phone.log.StructuredLog

/**
 * `InputController.AudioRouter` の Bluetooth SCO / LE Audio Headset 実装
 * (docs/03 §3.4.3)。切替シーケンス (§3.4.3.1)、BLUETOOTH_CONNECT runtime perm 取扱 (§3.4.3.2)、
 * 既知の race timing 制限 (§3.4.3.3)、`AudioManagerLike` 抽象化 (§3.4.3.4) を参照。
 */
class BtAudioRouter internal constructor(
    private val am: AudioManagerLike?,
) : InputController.AudioRouter {

    constructor(applicationContext: Context) : this(
        AndroidAudioManagerLike.fromContext(applicationContext),
    )

    private val log = StructuredLog("channel.glass.audio")

    @Volatile private var savedMode: Int = AudioManager.MODE_NORMAL
    @Volatile private var routed: Boolean = false

    override fun routeToGlassMic(): Boolean {
        val am = this.am
        if (am == null) {
            log.warn("audio_router_no_audio_manager")
            return false
        }
        if (routed) return true
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        // docs/03 §3.4.3.2: BT 周りは SecurityException も含めて全部 runCatching で吸収。
        val bt = runCatching { am.firstBtCommDevice() }
            .onFailure { log.warn("audio_router_enumerate_failed", it) }
            .getOrNull()

        if (bt == null) {
            log.info("audio_router_no_bt_device_fallback_phone_mic")
            am.mode = savedMode
            return false
        }
        val ok = runCatching { am.setCommunicationDevice(bt) }
            .onFailure { log.warn("audio_router_set_failed", it) }
            .getOrDefault(false)
        log.info(
            "audio_router_route",
            "device_type" to bt.type,
            "product" to bt.productName,
            "ok" to ok,
        )
        routed = ok
        if (!ok) am.mode = savedMode
        return ok
    }

    override fun restore() {
        val am = this.am ?: return
        if (routed) {
            runCatching { am.clearCommunicationDevice() }
            routed = false
        }
        am.mode = savedMode
        log.info("audio_router_restored", "mode" to savedMode)
    }
}
