package com.example.claudemobilehud.glass.gesture

import com.example.claudemobilehud.protocol.GestureKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Glass 内部 gesture 型 (docs/03 §4.6、FR-GL-10〜14)。protocol `GestureKind` との分離理由
 * (§4.6.1: DoubleTap は wire 送信せず local 操作にも使う) と `toWireKind` を参照。
 */
sealed interface GlassGesture {
    data object Tap : GlassGesture
    data object DoubleTap : GlassGesture
    data object SwipeForward : GlassGesture
    data object SwipeBack : GlassGesture
}

fun GlassGesture.toWireKind(): GestureKind? = when (this) {
    GlassGesture.Tap -> GestureKind.TAP
    GlassGesture.DoubleTap -> GestureKind.DOUBLE_TAP
    GlassGesture.SwipeForward -> GestureKind.SWIPE_FORWARD
    GlassGesture.SwipeBack -> GestureKind.SWIPE_BACK
}

/**
 * KeyEvent → Compose 画面の bus (docs/03 §4.6.2)。replay=0 / extraBufferCapacity=8 /
 * DROP_OLDEST の根拠 (P3-A: 新着優先) は §4.6.2 を参照。
 */
object GestureBus {
    private val _events = MutableSharedFlow<GlassGesture>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GlassGesture> = _events.asSharedFlow()

    fun emit(g: GlassGesture) {
        _events.tryEmit(g)
    }
}
