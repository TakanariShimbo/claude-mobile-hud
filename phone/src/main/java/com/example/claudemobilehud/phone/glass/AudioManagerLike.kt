package com.example.claudemobilehud.phone.glass

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * `BtAudioRouter` が見る `AudioManager` の最小 facade (docs/03 §3.4.3.4 / §3.4.3.5)。
 * `BtCommDeviceRef` value-object で Android 固有型 `AudioDeviceInfo` を test から隠す。
 */
internal interface AudioManagerLike {
    var mode: Int
    /** BT SCO / LE Audio Headset を 1 件返す。throws → caller (`BtAudioRouter`) が catch。 */
    fun firstBtCommDevice(): BtCommDeviceRef?
    /** route 確定。throws → caller が catch、boolean は実装側の成功失敗を反映。 */
    fun setCommunicationDevice(ref: BtCommDeviceRef): Boolean
    fun clearCommunicationDevice()
}

/** docs/03 §3.4.3.5: `AudioDeviceInfo` の最小代理。test は data class 実装で差し替え。 */
internal interface BtCommDeviceRef {
    val type: Int
    val productName: String
}

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
    // docs/03 §3.4.3.5: 一部端末で productName が SecurityException を投げるケースの防御。
    override val productName: String =
        runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")
}
