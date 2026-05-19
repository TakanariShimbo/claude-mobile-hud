package com.example.claudemobilehud.glass.ui.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.glass.R
import kotlinx.coroutines.delay

/**
 * HUD マスコット Konoha (docs/03 §4.11.7、FR-GL-80〜81)。
 * 192×208 cell × 8×9 grid の spritesheet atlas を Canvas + drawImage で再生する。
 * State → KonohaState の base mapping (§4.8.9) は [toKonohaState] に集約。
 * atlas 出典は姉妹プロジェクト `AndroidStudioProjects/MyPets` の `PetSprite.kt` (Konoha 1 体のみ移植)。
 */

private const val CELL_W = 192
private const val CELL_H = 208

enum class KonohaState(
    val row: Int,
    val durationsMs: IntArray,
) {
    Idle         (row = 0, durationsMs = intArrayOf(1680, 660, 660, 840, 840, 1920)),
    RunningRight (row = 1, durationsMs = intArrayOf(120, 120, 120, 120, 120, 120, 120, 220)),
    RunningLeft  (row = 2, durationsMs = intArrayOf(120, 120, 120, 120, 120, 120, 120, 220)),
    Waiting      (row = 6, durationsMs = intArrayOf(150, 150, 150, 150, 150, 260)),
    Running      (row = 7, durationsMs = intArrayOf(120, 120, 120, 120, 120, 220)),
    Review       (row = 8, durationsMs = intArrayOf(150, 150, 150, 150, 150, 280));

    val frameCount: Int get() = durationsMs.size
}

/** docs/03 §4.8.9 の base mapping 表に対応。RunningRight / RunningLeft は overlay 経路でのみ使うのでここからは到達しない。 */
internal fun ConversationStateHolder.State.toKonohaState(): KonohaState = when (this) {
    ConversationStateHolder.State.Idle -> KonohaState.Idle
    ConversationStateHolder.State.Listening -> KonohaState.Running
    is ConversationStateHolder.State.Confirming -> KonohaState.Review
    is ConversationStateHolder.State.PermissionConfirming -> KonohaState.Waiting
}

@Composable
fun KonohaSprite(
    state: KonohaState,
    modifier: Modifier = Modifier,
    scale: Float = 0.23f,
) {
    val atlas: ImageBitmap = ImageBitmap.imageResource(R.drawable.konoha_spritesheet)
    var frame by remember(state) { mutableIntStateOf(0) }

    LaunchedEffect(state) {
        while (true) {
            delay(state.durationsMs[frame].toLong())
            frame = (frame + 1) % state.frameCount
        }
    }

    Canvas(
        modifier = modifier.size((CELL_W * scale).dp, (CELL_H * scale).dp),
    ) {
        val srcX = frame * CELL_W
        val srcY = state.row * CELL_H
        drawImage(
            image = atlas,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(CELL_W, CELL_H),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
