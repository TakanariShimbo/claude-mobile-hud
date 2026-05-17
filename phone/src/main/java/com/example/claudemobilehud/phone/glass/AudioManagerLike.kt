package com.example.claudemobilehud.phone.glass

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * `BtAudioRouter` が見る `AudioManager` の最小 facade。Phase 5 で unit test 容易性を
 * 確保するため抽象化 (#183)。production は `AndroidAudioManagerLike`、test は `Fake`。
 *
 * 設計判断:
 *   - `AudioDeviceInfo` (Android 固有型) を直接露出させない。`BtCommDeviceRef` で
 *     必要 field (type / productName) だけ持つ value object に wrap し、router は
 *     `firstBtCommDevice()` から得たそれをそのまま `setCommunicationDevice` に渡す。
 *   - これで test 側は Android SDK 依存無しで全 fallback 経路を assert できる。
 */
internal interface AudioManagerLike {
    var mode: Int
    /** BT SCO / LE Audio Headset を 1 件返す。throws → caller (`BtAudioRouter`) が catch。 */
    fun firstBtCommDevice(): BtCommDeviceRef?
    /** route 確定。throws → caller が catch、boolean は実装側の成功失敗を反映。 */
    fun setCommunicationDevice(ref: BtCommDeviceRef): Boolean
    fun clearCommunicationDevice()
}

/**
 * `AudioDeviceInfo` の最小代理。BtAudioRouter が log / 識別に使う field のみ持つ。
 * production は `AndroidBtCommDeviceRef` で `AudioDeviceInfo` を内包、test は data class。
 */
internal interface BtCommDeviceRef {
    val type: Int
    val productName: String
}

/**
 * production 用 wrapper。`context.getSystemService(AudioManager::class.java)` が null の
 * 場合は `fromContext` が null を返し、router 側で `audio_router_no_audio_manager` 警告
 * を出して false 戻しする。
 */
internal class AndroidAudioManagerLike(private val am: AudioManager) : AudioManagerLike {
    override var mode: Int
        get() = am.mode
        set(value) { am.mode = value }

    override fun firstBtCommDevice(): BtCommDeviceRef? =
        am.availableCommunicationDevices
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            ?.let { AndroidBtCommDeviceRef(it) }

    override fun setCommunicationDevice(ref: BtCommDeviceRef): Boolean {
        val android = (ref as AndroidBtCommDeviceRef).device
        return am.setCommunicationDevice(android)
    }

    override fun clearCommunicationDevice() {
        am.clearCommunicationDevice()
    }

    companion object {
        fun fromContext(context: Context): AudioManagerLike? =
            context.getSystemService(AudioManager::class.java)?.let { AndroidAudioManagerLike(it) }
    }
}

internal class AndroidBtCommDeviceRef(val device: AudioDeviceInfo) : BtCommDeviceRef {
    override val type: Int = device.type
    override val productName: String =
        runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")
}
