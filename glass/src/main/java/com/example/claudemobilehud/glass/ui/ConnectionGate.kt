package com.example.claudemobilehud.glass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.claudemobilehud.glass.gesture.GestureBus
import com.example.claudemobilehud.glass.gesture.GlassGesture
import com.example.claudemobilehud.glass.glass.GlassBridge
import com.example.claudemobilehud.glass.ui.theme.TextGreenDim
import kotlinx.coroutines.flow.first

/**
 * Phone との CXR-L 接続が "session 開通" 状態のときだけ [content] を表示する gate。
 * Phase 3 §4.4 + FR-GL-02 / FR-GL-05。
 *
 * 未接続中は SessionSelect / Conversation がマウントされず、DoubleTap = 終了 のジェスチャを
 * 誰も拾わない。そのためここで明示的に DoubleTap だけ購読して [onExit] へ流す。接続成立で
 * if 分岐が剥がれて LaunchedEffect は終了する (DisposableEffect 不要)。
 */
@Composable
fun PhoneConnectionGate(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val open by GlassBridge.sessionOpen.collectAsStateWithLifecycle()

    if (!open) {
        // P1-B of 5b review: `collect` で DoubleTap を待つと、`onExit()`→`finish()` が
        // 非同期に終わるまで collector がループに残り、連打 DoubleTap で onExit が
        // 2 回呼ばれる race があった。`first { ... }` で 1 件で抜け、以降の DoubleTap は
        // bus に積まれるだけにする。
        LaunchedEffect(Unit) {
            GestureBus.events.first { it == GlassGesture.DoubleTap }
            onExit()
        }
    }

    if (open) {
        content()
    } else {
        val status by GlassBridge.status.collectAsStateWithLifecycle()
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (status) {
                    GlassBridge.Status.CONNECTED -> "phone 待機中…  (ダブル:終了)"
                    GlassBridge.Status.CONNECTING -> "phone 接続中…  (ダブル:終了)"
                    GlassBridge.Status.DISCONNECTED -> "phone と接続待ち  (ダブル:終了)"
                },
                color = TextGreenDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
