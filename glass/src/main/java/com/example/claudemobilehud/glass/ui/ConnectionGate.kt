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

/** docs/03 §4.11.4: CXR-L sessionOpen で content を gate。未接続中の DoubleTap を first で 1 件だけ受ける (P1-B)。 */
@Composable
fun PhoneConnectionGate(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val open by GlassBridge.sessionOpen.collectAsStateWithLifecycle()

    if (!open) {
        // docs/03 §4.11.4: first で 1 件抜け、連打 DoubleTap での多重 onExit を防ぐ。
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
