package com.example.claudemobilehud.glass.gesture

import com.example.claudemobilehud.protocol.GestureKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Glass 側で発生したジェスチャ。物理リモコン (KeyEvent) や CXR-L 受信ジェスチャを
 * 統一して扱うための内部 sealed 型。Phase 3 §4.4 / FR-GL-10〜14。
 *
 * **wire の [com.example.claudemobilehud.protocol.GestureKind] との対応**:
 *   - GlassGesture は Glass 内部 (UI dispatch) で使う型。
 *   - GestureKind は phone への wire 上で送る enum。
 *   - 変換は [toWireKind] (Glass→Phone 送信時に使う)。
 *
 * 二つに分けている理由: Glass 内部の DoubleTap は「会話画面で取消」「セッション選択で
 * 終了」など local 操作にも使う (wire 送信せず終わる) ため、protocol 層に直接結びつけない。
 */
sealed interface GlassGesture {
    data object Tap : GlassGesture
    data object DoubleTap : GlassGesture
    data object SwipeForward : GlassGesture
    data object SwipeBack : GlassGesture
}

/** Glass→Phone 送信用 wire enum へ変換。DoubleTap は wire に存在しないので明示 null 返し。 */
fun GlassGesture.toWireKind(): GestureKind? = when (this) {
    GlassGesture.Tap -> GestureKind.TAP
    GlassGesture.DoubleTap -> GestureKind.DOUBLE_TAP
    GlassGesture.SwipeForward -> GestureKind.SWIPE_FORWARD
    GlassGesture.SwipeBack -> GestureKind.SWIPE_BACK
}

/**
 * Activity の `dispatchKeyEvent` から emit → 各 Compose 画面が collect する単純な bus。
 *
 * - `replay = 0`: 過去 gesture を後着 subscriber に渡さない (HUD 操作は今ここの操作なので)。
 * - `extraBufferCapacity = 8`: 連打を吸収。
 * - P3-A of 5b review: `onBufferOverflow = DROP_OLDEST`。default の SUSPEND だと
 *   `tryEmit` が `false` を返して **新着** gesture が drop される。HUD 操作的に
 *   「古い gesture を捨てて新しいのを残す」のが直感的なので DROP_OLDEST に明示。
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
