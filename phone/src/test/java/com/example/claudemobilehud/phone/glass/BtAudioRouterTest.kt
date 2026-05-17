package com.example.claudemobilehud.phone.glass

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `BtAudioRouter` の fallback 経路を unit で押さえる (#183)。
 *
 * 実機で「Glass 接続 alive + BT SCO のみ失敗」を起こすのは CXR-L が BT 経由のため
 * 不可能 (BT を切ると Glass connection ごと落ちる)。`AudioManagerLike` 抽象化を
 * 使って次の 3 失敗経路 + 成功経路 + idempotent 経路を網羅する:
 *   1. AudioManager 不在 (system service null)
 *   2. BT SCO / LE 不在 (`firstBtCommDevice()` が null)
 *   3. `firstBtCommDevice()` が SecurityException 等を投げる (BLUETOOTH_CONNECT 不足)
 *   4. `setCommunicationDevice` が false を返す
 *   5. `setCommunicationDevice` が throw
 *   6. 成功経路 (routed=true、mode=IN_COMMUNICATION 維持、restore で復元)
 *   7. routed=true で再 routeToGlassMic (idempotent)
 */
class BtAudioRouterTest {

    @Test
    fun `returns false when AudioManager is unavailable`() {
        val router = BtAudioRouter(am = null)

        val ok = router.routeToGlassMic()

        assertFalse(ok)
    }

    @Test
    fun `restore is no-op when AudioManager is unavailable`() {
        val router = BtAudioRouter(am = null)
        // No exception expected.
        router.restore()
    }

    @Test
    fun `falls back when no BT comm device is available`() {
        val am = FakeAudioManager(
            initialMode = AudioManager.MODE_NORMAL,
            firstBtDevice = null,
        )
        val router = BtAudioRouter(am)

        val ok = router.routeToGlassMic()

        assertFalse(ok, "BT device 不在で false")
        assertEquals(AudioManager.MODE_NORMAL, am.mode, "mode は savedMode に巻き戻る")
        assertFalse(am.setCalled, "setCommunicationDevice は呼ばれない")
    }

    @Test
    fun `falls back when device enumeration throws`() {
        val am = FakeAudioManager(
            enumerateThrows = SecurityException("BLUETOOTH_CONNECT not granted"),
        )
        val router = BtAudioRouter(am)

        val ok = router.routeToGlassMic()

        assertFalse(ok, "enumerate 失敗で false")
        assertEquals(AudioManager.MODE_NORMAL, am.mode, "mode は savedMode に巻き戻る")
        // P2 of review: throw path で `routed` フラグが汚染されていないことを restore 経由で pin。
        router.restore()
        assertFalse(am.cleared, "throw 経路では routed=false のままで clear しない")
    }

    @Test
    fun `falls back when setCommunicationDevice returns false`() {
        val device = FakeBtCommDevice(type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Glass-1")
        val am = FakeAudioManager(
            initialMode = AudioManager.MODE_NORMAL,
            firstBtDevice = device,
            setReturns = false,
        )
        val router = BtAudioRouter(am)

        val ok = router.routeToGlassMic()

        assertFalse(ok)
        assertTrue(am.setCalled)
        assertEquals(AudioManager.MODE_NORMAL, am.mode, "ok=false で mode を savedMode に巻き戻す")
    }

    @Test
    fun `falls back when setCommunicationDevice throws`() {
        val device = FakeBtCommDevice(type = AudioDeviceInfo.TYPE_BLE_HEADSET, productName = "Glass-LE")
        val am = FakeAudioManager(
            firstBtDevice = device,
            setThrows = SecurityException("perm denied"),
        )
        val router = BtAudioRouter(am)

        val ok = router.routeToGlassMic()

        assertFalse(ok)
        assertEquals(AudioManager.MODE_NORMAL, am.mode, "throw でも mode を savedMode に巻き戻す")
        // P2 of review: set throw でも routed=false が維持される (restore で clear しない)。
        router.restore()
        assertFalse(am.cleared, "set throw 経路でも routed=false のままで clear しない")
    }

    @Test
    fun `routes successfully when BT device is set`() {
        val device = FakeBtCommDevice(type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Glass-1")
        val am = FakeAudioManager(
            initialMode = AudioManager.MODE_NORMAL,
            firstBtDevice = device,
            setReturns = true,
        )
        val router = BtAudioRouter(am)

        val ok = router.routeToGlassMic()

        assertTrue(ok)
        assertTrue(am.setCalled)
        assertEquals(device, am.setRef)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode, "成功時は IN_COMMUNICATION のまま")
    }

    @Test
    fun `restore clears communication device and restores saved mode`() {
        val device = FakeBtCommDevice(type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Glass-1")
        val am = FakeAudioManager(
            initialMode = AudioManager.MODE_NORMAL,
            firstBtDevice = device,
            setReturns = true,
        )
        val router = BtAudioRouter(am)
        router.routeToGlassMic()

        router.restore()

        assertTrue(am.cleared, "clearCommunicationDevice が呼ばれる")
        assertEquals(AudioManager.MODE_NORMAL, am.mode, "savedMode に復元")
    }

    @Test
    fun `re-routing when already routed is idempotent and returns true`() {
        val device = FakeBtCommDevice(type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Glass-1")
        val am = FakeAudioManager(firstBtDevice = device, setReturns = true)
        val router = BtAudioRouter(am)
        router.routeToGlassMic()
        am.setCalled = false // ← 2 回目で set が呼ばれない事を見たい

        val ok2 = router.routeToGlassMic()

        assertTrue(ok2)
        assertFalse(am.setCalled, "routed=true なら 2 回目は何もしない")
    }

    @Test
    fun `restore is no-op when not routed`() {
        val am = FakeAudioManager(firstBtDevice = null)
        val router = BtAudioRouter(am)
        // routeToGlassMic 失敗 (BT 不在) 後の restore: clearCommunicationDevice は呼ばない。
        router.routeToGlassMic()

        router.restore()

        assertFalse(am.cleared, "routed=false 経路では clear しない")
    }

    @Test
    fun `productName is preserved through facade`() {
        val device = FakeBtCommDevice(type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Rokid-Glass")
        val am = FakeAudioManager(firstBtDevice = device, setReturns = true)
        val router = BtAudioRouter(am)

        router.routeToGlassMic()

        assertNotNull(am.setRef)
        assertEquals("Rokid-Glass", am.setRef?.productName)
    }

    @Test
    fun `setRef is null before any routing`() {
        val am = FakeAudioManager(firstBtDevice = null)
        assertNull(am.setRef)
    }
}

internal data class FakeBtCommDevice(
    override val type: Int,
    override val productName: String,
) : BtCommDeviceRef

internal class FakeAudioManager(
    initialMode: Int = AudioManager.MODE_NORMAL,
    private val firstBtDevice: BtCommDeviceRef? = null,
    private val enumerateThrows: Throwable? = null,
    private val setReturns: Boolean = true,
    private val setThrows: Throwable? = null,
) : AudioManagerLike {
    override var mode: Int = initialMode

    var setCalled: Boolean = false
    var setRef: BtCommDeviceRef? = null
    var cleared: Boolean = false

    override fun firstBtCommDevice(): BtCommDeviceRef? {
        enumerateThrows?.let { throw it }
        return firstBtDevice
    }

    override fun setCommunicationDevice(ref: BtCommDeviceRef): Boolean {
        setCalled = true
        setRef = ref
        setThrows?.let { throw it }
        return setReturns
    }

    override fun clearCommunicationDevice() {
        cleared = true
    }
}
