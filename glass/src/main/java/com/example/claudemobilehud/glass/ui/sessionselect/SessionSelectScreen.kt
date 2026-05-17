package com.example.claudemobilehud.glass.ui.sessionselect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.claudemobilehud.glass.gesture.GestureBus
import com.example.claudemobilehud.glass.gesture.GlassGesture
import com.example.claudemobilehud.glass.glass.GlassBridge
import com.example.claudemobilehud.glass.ui.theme.TextChevronDim
import com.example.claudemobilehud.glass.ui.theme.TextGreen
import com.example.claudemobilehud.glass.ui.theme.TextGreenDim
import com.example.claudemobilehud.glass.ui.theme.TextInactive
import com.example.claudemobilehud.protocol.SessionSummaryPayload

/**
 * Glass session 選択画面 (docs/03 §4.9、FR-GL-20〜22)。前後でカーソル移動 / Tap 決定 /
 * DoubleTap 終了。index を単一 State として保持する根拠 (§4.9.1) と current 追従を初回のみ
 * に限定する根拠 (§4.9.2 P2-A) は §4.9 を参照。
 */
@Composable
fun SessionSelectScreen(onSelected: () -> Unit, onExit: () -> Unit) {
    val sessions by GlassBridge.sessions.collectAsStateWithLifecycle()
    val current by GlassBridge.currentSessionId.collectAsStateWithLifecycle()

    // docs/03 §4.9.1: 単一 State として保持 (key 付き remember は古い State を握る hazard)。
    var index by remember { mutableIntStateOf(0) }

    // docs/03 §4.9.2 (P2-A): current 追従は初回マウントのみ。以降はユーザ意志を尊重 + size clamp。
    var didInitialAlign by remember { mutableStateOf(false) }
    LaunchedEffect(sessions, current) {
        if (!didInitialAlign && sessions.isNotEmpty()) {
            val byCurrent = sessions.indexOfFirst { it.id == current }
            if (byCurrent >= 0) index = byCurrent
            didInitialAlign = true
        }
        if (sessions.isEmpty()) {
            index = 0
        } else if (index >= sessions.size) {
            index = sessions.size - 1
        }
    }

    LaunchedEffect(Unit) {
        GestureBus.events.collect { g ->
            when (g) {
                GlassGesture.SwipeForward -> if (sessions.isNotEmpty()) {
                    index = (index + 1).coerceAtMost(sessions.size - 1)
                }
                GlassGesture.SwipeBack -> if (sessions.isNotEmpty()) {
                    index = (index - 1).coerceAtLeast(0)
                }
                GlassGesture.Tap -> {
                    val selected = sessions.getOrNull(index) ?: return@collect
                    GlassBridge.sendSelectSession(selected.id)
                    onSelected()
                }
                GlassGesture.DoubleTap -> onExit()
            }
        }
    }

    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("アクティブなセッション無し", color = TextGreenDim, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("ダブル:終了", color = TextGreenDim, fontSize = 11.sp)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "前後:選択 / タップ:決定 / ダブル:終了",
            color = TextGreenDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        sessions.forEachIndexed { i, s ->
            SessionRow(session = s, focused = i == index)
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummaryPayload, focused: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Text(
            text = if (focused) "▶ " else "   ",
            color = if (focused) TextGreen else TextChevronDim,
        )
        Text(
            text = session.label,
            color = if (focused) TextGreen else TextInactive,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "(${session.messageCount})",
            color = if (focused) TextGreenDim else TextInactive,
            fontSize = 12.sp,
        )
    }
}
