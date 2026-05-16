// AppLifecycleController state machine の純 JVM unit test (P3-9 同思想)。
// FgsOperations を fake に差し替えて Android Context 依存を抽象化する。

package com.example.claudemobilehud.phone.service

import android.content.Context
import com.example.claudemobilehud.phone.service.AppLifecycleController.FgsKind
import com.example.claudemobilehud.phone.service.AppLifecycleController.FgsLifecycle
import com.example.claudemobilehud.phone.service.AppLifecycleController.GlassFgsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLifecycleControllerTest {

    private class FakeFgsOps : AppLifecycleController.FgsOperations {
        val log = mutableListOf<String>()
        override fun startChannelFgs(context: Context) { log += "start_channel" }
        override fun stopChannelFgs(context: Context) { log += "stop_channel" }
        override fun startGlassFgs(context: Context) { log += "start_glass" }
        override fun stopGlassFgs(context: Context) { log += "stop_glass" }
        override fun startMicFgs(context: Context) { log += "start_mic" }
        override fun stopMicFgs(context: Context) { log += "stop_mic" }
    }

    private val ctx: Context = mockContext()

    private fun TestScope.newCtrl(timeoutMs: Long = 5_000L): Pair<AppLifecycleController, FakeFgsOps> {
        val ops = FakeFgsOps()
        // coroutineContext + SupervisorJob() で controller scope を test と独立 Job に。
        // controller の launch は test dispatcher 上に乗るので advanceUntilIdle で制御可能。
        val ctrl = AppLifecycleController(
            fgsOps = ops,
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            startingTimeoutMs = timeoutMs,
        )
        return ctrl to ops
    }

    @Test
    fun `startChannel then stopChannel toggles channelRunning + invokes ops`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, ops) = newCtrl()
        ctrl.startChannel(ctx)
        assertEquals(true, ctrl.channelRunning.value)
        assertEquals(listOf("start_channel"), ops.log)
        ctrl.startChannel(ctx) // idempotent
        assertEquals(listOf("start_channel"), ops.log)
        ctrl.stopChannel(ctx)
        assertEquals(false, ctrl.channelRunning.value)
        assertEquals(listOf("start_channel", "stop_channel"), ops.log)
    }

    @Test
    fun `Off to Starting to Running on both ON_CREATE`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, ops) = newCtrl()
        ctrl.startGlassSession(ctx)
        assertEquals(GlassFgsState.Starting, ctrl.glassState.value)
        assertTrue(ops.log.containsAll(listOf("start_glass", "start_mic")))

        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Starting, ctrl.glassState.value, "片方のみでは遷移しない")
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Running, ctrl.glassState.value)
    }

    @Test
    fun `Running to Stopping false to Off on stopGlass plus both ON_DESTROY`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, _) = newCtrl()
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Running, ctrl.glassState.value)

        ctrl.stopGlassSession(ctx)
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_DESTROY, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_DESTROY, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Off, ctrl.glassState.value)
    }

    @Test
    fun `startGlassSession during Stopping reserves restart and bounces to Starting`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, ops) = newCtrl()
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        ctrl.stopGlassSession(ctx)
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)

        ctrl.startGlassSession(ctx)
        assertEquals(GlassFgsState.Stopping(restartAfter = true), ctrl.glassState.value)

        // 両 ON_DESTROY 到達で restart 予約消化 → Starting + ops 再発火。
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_DESTROY, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_DESTROY, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Starting, ctrl.glassState.value)
        // start_glass / start_mic が 2 回ずつ呼ばれている (初回 + restart)
        val startGlassCalls = ops.log.count { it == "start_glass" }
        val startMicCalls = ops.log.count { it == "start_mic" }
        assertEquals(2, startGlassCalls, "ops.log=${ops.log}")
        assertEquals(2, startMicCalls)
    }

    @Test
    fun `stopGlassSession during Stopping(restartAfter=true) cancels restart`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, _) = newCtrl()
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        ctrl.stopGlassSession(ctx)
        ctrl.startGlassSession(ctx) // restartAfter = true
        assertEquals(GlassFgsState.Stopping(restartAfter = true), ctrl.glassState.value)
        ctrl.stopGlassSession(ctx) // cancel restart
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
        // 両 ON_DESTROY 到達で Off に確定 (Starting に bounce しないことを確認)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_DESTROY, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_DESTROY, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Off, ctrl.glassState.value)
    }

    @Test
    fun `onGlassDisconnected from Running transitions to Stopping(false)`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, _) = newCtrl()
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Running, ctrl.glassState.value)

        ctrl.onGlassDisconnected(ctx)
        runCurrent()
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
    }

    @Test
    fun `Starting timeout forces Stopping(false) when ON_CREATE never both arrive`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, ops) = newCtrl(timeoutMs = 1_000L)
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        // Mic は ON_CREATE を出さないまま timeout 経過
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
        // 異常系の clean-up で stop_* がそれぞれ叩かれている
        assertTrue(ops.log.contains("stop_glass"), "ops.log=${ops.log}")
        assertTrue(ops.log.contains("stop_mic"), "ops.log=${ops.log}")
    }

    @Test
    fun `onGlassDisconnected from Starting transitions to Stopping(false)`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, ops) = newCtrl()
        ctrl.startGlassSession(ctx)
        assertEquals(GlassFgsState.Starting, ctrl.glassState.value)

        ctrl.onGlassDisconnected(ctx)
        runCurrent()
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
        // P3-8: Starting 中の disconnect でも stop ops が呼ばれる + 5s watchdog がキャンセル
        // されていることを次の advance で確認 (timer 経過しても Stopping のまま)。
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
    }

    @Test
    fun `startGlassSession while Stopping(true) stays Stopping(true)`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, _) = newCtrl()
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        ctrl.stopGlassSession(ctx)
        ctrl.startGlassSession(ctx) // → Stopping(restartAfter=true)
        assertEquals(GlassFgsState.Stopping(restartAfter = true), ctrl.glassState.value)
        // P3-9: 追加の startGlassSession は冪等 (再度 true にしても変わらない)
        ctrl.startGlassSession(ctx)
        assertEquals(GlassFgsState.Stopping(restartAfter = true), ctrl.glassState.value)
    }

    @Test
    fun `Starting then both ON_CREATE then partial ON_DESTROY transitions to Stopping(false)`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, ops) = newCtrl(timeoutMs = 10_000L)
        ctrl.startGlassSession(ctx)
        // 部分起動: Glass だけ ON_CREATE して即 ON_DESTROY (Glass FGS が起動直後にクラッシュ)。
        // Mic は ON_CREATE 来ないまま。両 false で P2-1 分岐に入り Stopping(false) に。
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_DESTROY, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)
        // ops は stop_glass / stop_mic を含む (P2-1 の cleanup) — 起動分の start_* + cleanup の stop_*
        assertTrue(ops.log.count { it == "stop_glass" } >= 1, "ops.log=${ops.log}")
        assertTrue(ops.log.count { it == "stop_mic" } >= 1)
    }

    @Test
    fun `Running plus glass ON_DESTROY first then mic ON_DESTROY transitions cleanly to Off`() = runTest(UnconfinedTestDispatcher()) {
        // 4c1 review P2-9: 自然切断時 (onGlassDisconnected → stopGlassFgs / stopMicFgs) で
        // ON_DESTROY が片方ずつ届く順番に対する不変条件を test で固定する。
        val (ctrl, _) = newCtrl()
        ctrl.startGlassSession(ctx)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_CREATE, ctx)
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        ctrl.onGlassDisconnected(ctx)
        runCurrent()
        assertEquals(GlassFgsState.Stopping(restartAfter = false), ctrl.glassState.value)

        // Glass FGS が先に ON_DESTROY (mic はまだ true)
        ctrl.onFgsLifecycle(FgsKind.GLASS_CONNECTION, FgsLifecycle.ON_DESTROY, ctx)
        runCurrent()
        assertEquals(
            GlassFgsState.Stopping(restartAfter = false),
            ctrl.glassState.value,
            "片方残っている間は Stopping のまま",
        )
        ctrl.onFgsLifecycle(FgsKind.MIC, FgsLifecycle.ON_DESTROY, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Off, ctrl.glassState.value)
    }

    @Test
    fun `CHANNEL FgsLifecycle is independent and does not touch glass state`() = runTest(UnconfinedTestDispatcher()) {
        val (ctrl, _) = newCtrl()
        ctrl.startGlassSession(ctx)
        assertEquals(GlassFgsState.Starting, ctrl.glassState.value)
        ctrl.onFgsLifecycle(FgsKind.CHANNEL, FgsLifecycle.ON_CREATE, ctx)
        runCurrent()
        assertEquals(GlassFgsState.Starting, ctrl.glassState.value)
    }

    /**
     * AppLifecycleController は Context を受け取りはするが、メソッド内で .startService 等を
     * 直接叩かない (FgsOperations を介す)。よって test では mock 不要だが Kotlin の null 不可の
     * 制約上、簡易な fake が必要。
     */
    private fun mockContext(): Context = object : android.content.ContextWrapper(null) {}
}
