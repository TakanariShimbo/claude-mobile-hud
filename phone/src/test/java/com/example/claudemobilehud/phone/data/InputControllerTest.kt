// InputController の session-scoped draft 動作 + transcription delta/completed の
// input への反映を test。AudioRouter 経路は 4c 完成後にカバー。

package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.transcription.MicCaptureSource
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
import com.example.claudemobilehud.phone.data.transcription.TranscriptionConfig
import com.example.claudemobilehud.phone.data.transcription.TranscriptionEvent
import com.example.claudemobilehud.phone.data.transcription.TranscriptionTransport
import com.example.claudemobilehud.protocol.MicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InputControllerTest {

    @Test
    fun `update writes per-session draft and switches with setCurrentSession`() = runTest(UnconfinedTestDispatcher()) {
        val ctrl = InputController(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        ctrl.setCurrentSession("A")
        ctrl.update("hello A")
        assertEquals("hello A", ctrl.text.value)

        ctrl.setCurrentSession("B")
        runCurrent()
        assertEquals("", ctrl.text.value, "different session shows empty draft")
        ctrl.update("hi B")
        assertEquals("hi B", ctrl.text.value)

        ctrl.setCurrentSession("A")
        runCurrent()
        assertEquals("hello A", ctrl.text.value, "session A draft preserved")
    }

    @Test
    fun `clear removes only the current session draft`() = runTest(UnconfinedTestDispatcher()) {
        val ctrl = InputController(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        ctrl.setCurrentSession("A"); ctrl.update("a")
        ctrl.setCurrentSession("B"); ctrl.update("b")
        ctrl.clear() // current is B
        assertEquals("", ctrl.text.value)
        ctrl.setCurrentSession("A"); runCurrent()
        assertEquals("a", ctrl.text.value, "A draft survives clearing B")
    }

    @Test
    fun `startFromGlass without router falls back to PHONE_FALLBACK and warns`() = runTest(UnconfinedTestDispatcher()) {
        val ctrl = InputController(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        ctrl.setCurrentSession("A")
        ctrl.startFromGlass("k")
        runCurrent()
        assertEquals(MicSource.PHONE_FALLBACK, ctrl.micSource.value)
    }

    @Test
    fun `startFromGlass with successful router sets micSource to GLASS and restore on stop`() = runTest(UnconfinedTestDispatcher()) {
        val router = object : InputController.AudioRouter {
            var restored = false
            override fun routeToGlassMic(): Boolean = true
            override fun restore() { restored = true }
        }
        val ctrl = InputController(
            audioRouter = router,
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        ctrl.setCurrentSession("A")
        ctrl.startFromGlass("k")
        runCurrent()
        assertEquals(MicSource.GLASS, ctrl.micSource.value)
        ctrl.stop()
        runCurrent()
        assertEquals(MicSource.PHONE_FALLBACK, ctrl.micSource.value)
        assertEquals(true, router.restored)
    }

    @Test
    fun `startWithPhoneMic sets micSource to PHONE_FALLBACK`() = runTest(UnconfinedTestDispatcher()) {
        val ctrl = InputController(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        ctrl.setCurrentSession("A")
        ctrl.startWithPhoneMic("k")
        runCurrent()
        assertEquals(MicSource.PHONE_FALLBACK, ctrl.micSource.value)
    }

    @Test
    fun `startFromGlass without router emits BtScoUnavailable error`() = runTest(UnconfinedTestDispatcher()) {
        val ctrl = InputController(scope = CoroutineScope(coroutineContext + SupervisorJob()))
        ctrl.setCurrentSession("A")
        val collected = mutableListOf<com.example.claudemobilehud.phone.data.error.PhoneWireError>()
        val j = launch { ctrl.errors.collect { collected += it } }
        ctrl.startFromGlass("k")
        runCurrent()
        j.cancel()
        assertEquals(1, collected.size)
        assertTrue(collected[0] is com.example.claudemobilehud.phone.data.error.PhoneWireError.Glass.BtScoUnavailable)
    }

    @Test
    fun `startFromGlass with failing router also emits BtScoUnavailable`() = runTest(UnconfinedTestDispatcher()) {
        val failing = object : InputController.AudioRouter {
            override fun routeToGlassMic(): Boolean = false
            override fun restore() {}
        }
        val ctrl = InputController(
            audioRouter = failing,
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        ctrl.setCurrentSession("A")
        val collected = mutableListOf<com.example.claudemobilehud.phone.data.error.PhoneWireError>()
        val j = launch { ctrl.errors.collect { collected += it } }
        ctrl.startFromGlass("k")
        runCurrent()
        j.cancel()
        assertEquals(1, collected.size)
        assertEquals(MicSource.PHONE_FALLBACK, ctrl.micSource.value)
    }

    @Test
    fun `transcription completed appends to current session input with trailing space`() = runTest(UnconfinedTestDispatcher()) {
        // 注入 transcription client + fake transport / mic で SessionReady → Completed を流す。
        val ev = MutableSharedFlow<TranscriptionEvent>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        val transport = object : TranscriptionTransport {
            override val events: Flow<TranscriptionEvent> = ev.asSharedFlow()
            override fun connect() {}
            override fun sendAudio(base64: String) {}
            override fun close() {}
        }
        val mic = object : MicCaptureSource {
            override val frames: Flow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
            override fun start() {}
            override fun stop() {}
        }
        val transcription = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        val ctrl = InputController(
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            transcription = transcription,
        )
        ctrl.setCurrentSession("A")
        ctrl.startWithPhoneMic("k")
        runCurrent()
        ev.emit(TranscriptionEvent.SessionReady); runCurrent()
        ev.emit(TranscriptionEvent.Delta("hello")); runCurrent()
        assertEquals("hello", ctrl.text.value, "partial should reach current session input")
        ev.emit(TranscriptionEvent.Completed("hello world")); runCurrent()
        // Completed 後は session に確定文字列が書かれる (trailing space は次の partial
        // と整合させるため transcriptBase 側にだけ仕込まれ、UI 表示には現れない)。
        assertEquals("hello world", ctrl.text.value)
    }
}
