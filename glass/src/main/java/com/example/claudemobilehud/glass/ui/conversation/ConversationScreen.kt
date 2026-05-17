package com.example.claudemobilehud.glass.ui.conversation

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.claudemobilehud.glass.ScreenAwakeManager
import com.example.claudemobilehud.glass.gesture.GestureBus
import com.example.claudemobilehud.glass.glass.GlassBridge
import com.example.claudemobilehud.glass.ui.conversation.ConversationStateHolder.PermissionChoice
import com.example.claudemobilehud.glass.ui.conversation.ConversationStateHolder.SendChoice
import com.example.claudemobilehud.glass.ui.conversation.ConversationStateHolder.State
import com.example.claudemobilehud.glass.ui.theme.TextGreen
import com.example.claudemobilehud.glass.ui.theme.TextGreenDim
import com.example.claudemobilehud.glass.ui.theme.TextInactive
import com.example.claudemobilehud.protocol.ChatMessagePayload
import com.example.claudemobilehud.protocol.MessageRole
import com.example.claudemobilehud.protocol.PendingPermissionPayload
import com.example.claudemobilehud.protocol.TranscriptState

/**
 * HUD 会話画面 (docs/03 §4.4 / §4.8、FR-GL-30〜33 / FR-GL-50〜62)。
 * onBack 間接参照 (§4.8.1)、1 swipe = 1 行 (§4.8.2)、CONFIRMING 中の auto-scroll 抑制
 * (§4.8.3)、MessageList key (§4.8.4 P2-F)、INCOMING 強調 (§4.8.5)、フォント定数集約
 * (§4.8.6 P3-B)、ScreenAwakeManager 連動 (§4.8.7)、空 input Confirming 防御 (§4.8.8 P3-E)
 * を参照。
 */
@Composable
fun ConversationScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val phoneState by GlassBridge.phoneState.collectAsStateWithLifecycle()
    val messages by GlassBridge.messages.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    // docs/03 §4.8.1: NavHost recomposition で別インスタンスに変わるため rememberUpdatedState で間接参照。
    val currentOnBack = rememberUpdatedState(onBack)
    val holder = remember {
        ConversationStateHolder(
            phoneState = GlassBridge.phoneState,
            onBack = { currentOnBack.value() },
            scope = scope,
        )
    }
    val state by holder.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    LaunchedEffect(holder) {
        GestureBus.events.collect { holder.onGesture(it) }
    }
    // docs/03 §4.8.2: 1 swipe = LINE_HEIGHT_SP 分の animateScrollBy。
    val lineHeightPx = with(LocalDensity.current) { LINE_HEIGHT_SP.sp.toPx() }
    LaunchedEffect(holder) {
        holder.scrollRequest.collect { lines ->
            listState.animateScrollBy(lines * lineHeightPx)
        }
    }

    // docs/03 §4.8.7: STARTED 連動で SCREEN_BRIGHT_WAKE_LOCK を握る。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val handle = ScreenAwakeManager.acquireWhileStarted(context, lifecycleOwner.lifecycle)
        onDispose { handle.close() }
    }

    val frameHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
    // docs/03 §4.8.3: CONFIRMING 中は確認対象を見失わないよう auto-scroll を抑制。
    val autoScroll = state !is State.Confirming && state !is State.PermissionConfirming

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        HintLine(state)
        Spacer(Modifier.height(4.dp))
        ChatFrame(
            modifier = Modifier
                .fillMaxWidth()
                .height(frameHeight),
        ) {
            MessageList(
                messages = messages,
                state = listState,
                autoScroll = autoScroll,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        when (val s = state) {
            is State.Confirming -> ConfirmBar(input = phoneState.inputText, choice = s.choice)
            is State.PermissionConfirming -> PermissionConfirmBar(pending = s.pending, choice = s.choice)
            State.Listening -> InputLine(
                input = phoneState.inputText,
                transcript = phoneState.transcriptText,
                transcriptState = phoneState.transcriptState,
            )
            State.Idle -> InputLine(
                input = phoneState.inputText,
                transcript = phoneState.transcriptText,
                transcriptState = TranscriptState.IDLE, // idle 表示は input のみ
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessagePayload>,
    state: LazyListState,
    autoScroll: Boolean,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("メッセージなし", color = TextGreenDim, fontSize = 14.sp)
        }
        return
    }
    // docs/03 §4.8.4 (P2-F): size + last.id + last.text.length で同一 size 別 id を補足。
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.id,
        messages.lastOrNull()?.text?.length,
        autoScroll,
    ) {
        if (autoScroll && messages.isNotEmpty()) state.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = state,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            MessageRow(msg)
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessagePayload) {
    val prefix = when (message.role) {
        MessageRole.OUTGOING -> "🧑"
        MessageRole.INCOMING -> "🤖"
        MessageRole.SYSTEM -> "ℹ️"
    }
    // docs/03 §4.8.5: 強調は INCOMING のみ (HUD 上で読みたい内容を識別しやすく)。
    val isReply = message.role == MessageRole.INCOMING
    val color = if (isReply) TextGreen else TextInactive
    val weight = if (isReply) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(prefix, color = color, fontSize = MESSAGE_FONT_SP.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            message.text,
            color = color,
            fontSize = MESSAGE_FONT_SP.sp,
            fontWeight = weight,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InputLine(
    input: String,
    transcript: String,
    transcriptState: TranscriptState,
) {
    val isListening = transcriptState == TranscriptState.LISTENING
    val display = when {
        isListening && transcript.isNotEmpty() -> transcript
        isListening -> "…"
        input.isNotEmpty() -> input
        else -> ""
    }
    if (display.isEmpty()) return
    val cursor = if (isListening) "▎" else ""
    Text(
        "🧑 $display$cursor",
        color = if (isListening) TextGreen else TextGreenDim,
        fontSize = MESSAGE_FONT_SP.sp,
    )
}

@Composable
private fun ConfirmBar(input: String, choice: SendChoice) {
    // docs/03 §4.8.8 (P3-E): 空 input Confirming の防御。プレビュー行をスキップ。
    Column {
        if (input.isNotEmpty()) {
            Text(
                "🧑 $input",
                color = TextGreenDim,
                fontSize = MESSAGE_FONT_SP.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChoiceItem("送信", focused = choice == SendChoice.SEND)
            Spacer(Modifier.width(16.dp))
            ChoiceItem("取消", focused = choice == SendChoice.CANCEL)
        }
    }
}

@Composable
private fun PermissionConfirmBar(pending: PendingPermissionPayload, choice: PermissionChoice) {
    Column {
        Text(
            "🛠 ${pending.toolName}",
            color = TextGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        if (pending.description.isNotEmpty()) {
            Text(
                pending.description,
                color = TextGreenDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChoiceItem("拒否", focused = choice == PermissionChoice.DENY)
            Spacer(Modifier.width(16.dp))
            ChoiceItem("許可", focused = choice == PermissionChoice.ALLOW)
        }
    }
}

@Composable
private fun ChoiceItem(label: String, focused: Boolean) {
    Text(
        text = if (focused) "▶ $label" else "  $label",
        color = if (focused) TextGreen else TextGreenDim,
        fontSize = 13.sp,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
    )
}

// docs/03 §4.8.6 (P3-B): HUD 文字サイズ + 行高の集約。LINE_HEIGHT_SP は MESSAGE_FONT_SP の約 1.75 倍 (実機計測)。
private const val MESSAGE_FONT_SP = 12f
private const val LINE_HEIGHT_SP = 21f

@Composable
private fun HintLine(state: State) {
    val text = when (state) {
        State.Idle -> "タップ:録音 / 前後:スクロール / ダブル:戻る"
        State.Listening -> "タップ:停止 / 前後:スクロール / ダブル:取消"
        is State.Confirming -> "前後:選択 / タップ:決定 / ダブル:取消"
        is State.PermissionConfirming -> "前後:選択 / タップ:決定 / ダブル:拒否"
    }
    Text(text, color = TextGreenDim, fontSize = 11.sp)
}
