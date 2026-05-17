package com.example.claudemobilehud.phone.glass

import android.content.Context
import android.media.AudioManager
import com.example.claudemobilehud.phone.data.InputController
import com.example.claudemobilehud.phone.log.StructuredLog

/**
 * `InputController.AudioRouter` を Bluetooth SCO / LE Audio Headset で実装する
 * (docs/03 §3.2.5、Glass mic 経路の BtScoUnavailable contract は §3.2.5.6)。
 *
 * Rokid Glass は BT 通話デバイスとして見えるため、phone の Communication Device を
 * これに切り替えると `AudioRecord` が自然と glass mic を読むようになる (CXR-L は不要)。
 *
 * - `routeToGlassMic` / `restore` は対で呼ぶ契約。InputController が責任を持つ。
 * - 失敗 (BT device 不在 / `SecurityException` / 権限不足) は false 戻し、呼び出し側で
 *   PHONE_FALLBACK + BtScoUnavailable error を emit する (4b2 P1-3 と整合)。
 *
 * **既知の制限** (P2-6 of 4c1 review):
 *   `AudioManager.setCommunicationDevice` は即時 boolean を返すが、BT SCO の実際の
 *   リンク確立は非同期 (~数百 ms)。短時間で routeToGlassMic → restore を連打すると
 *   `clearCommunicationDevice` がリンク確立前に呼ばれ、no-op になりうる。本実装では
 *   InputController が transcription 起動から少なくとも数百 ms は録音を続ける契約に
 *   依存して race を回避している。厳密に同期したい場合は
 *   `addOnCommunicationDeviceChangedListener` で gating する。
 *
 * **P3-5**: `BLUETOOTH_CONNECT` は API 31+ で runtime permission。Manifest だけでは
 *   足りず実行時に granted を確認するか、SecurityException を全 BT path で catch する
 *   必要がある。本実装は後者を採用 (全ての BT 操作を `runCatching` で囲う)。
 *
 * **Phase 5 #183**: `AudioManagerLike` で抽象化し fallback path を unit test で押さえる。
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
        // P3-5: BLUETOOTH_CONNECT runtime perm 不足 / その他 SecurityException も
        // setCommunicationDevice / productName 経路で起こりうるため、BT 周り全体を
        // runCatching で囲う。
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
