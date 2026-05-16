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
 * Phase 3 §4.4 + FR-GL-30〜33 / FR-GL-50〜62 の会話画面。
 *
 * - 履歴 (messages) は HUD 中央のフレーム内 LazyColumn に表示。
 * - 入力中テキスト (inputText) と録音中 partial (transcriptText) は画面下に。
 * - mode 別に HintLine / 入力 UI が切り替わる。
 *
 * gesture ハンドリングは [ConversationStateHolder] に委譲。state は holder.state を 1 つだけ
 * 観測する (mode + cursor の組合せ)。
 */
@Composable
fun ConversationScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val phoneState by GlassBridge.phoneState.collectAsStateWithLifecycle()
    val messages by GlassBridge.messages.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    // onBack ラムダは親 NavHost の recomposition で別インスタンスに差し替わり得るので、
    // remember に直接キャプチャすると初回値を握り続ける。rememberUpdatedState で間接参照する。
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
    // 1 swipe = 1 行分のスクロール量 (12sp 本文 + Material default の line-height multiplier
    // ≈ 16sp 程度) を pixel に変換して animateScrollBy。コンテンツ末端は自動でクランプ。
    val lineHeightPx = with(LocalDensity.current) { LINE_HEIGHT_SP.sp.toPx() }
    LaunchedEffect(holder) {
        holder.scrollRequest.collect { lines ->
            listState.animateScrollBy(lines * lineHeightPx)
        }
    }

    // display 電源は ScreenAwakeManager に一任。会話画面が生きている間 STARTED/STOPPED
    // 連動で SCREEN_BRIGHT_WAKE_LOCK を握る。詳細は ScreenAwakeManager を参照。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val handle = ScreenAwakeManager.acquireWhileStarted(context, lifecycleOwner.lifecycle)
        onDispose { handle.close() }
    }

    val frameHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
    // CONFIRMING / PERMISSION_CONFIRMING のあいだは最新メッセージ追従を一旦止める
    // (確認操作中に勝手にスクロールされると気持ち悪いので)。
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
    // P2-F of 5b review: key に `lastOrNull()?.id` を含める。size + last.text.length が
    // 同じでも別 message に切替わった場合 (例: 旧末尾削除 + 新追加 → 同 size、別 id) を
    // 確実に補足する。streaming reply の text 伸長は length で補足。
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
    // 強調は AI の返信 (INCOMING) に集中させる。HUD 上で「読みたい内容」だけが
    // 明るく + 太字になるので、ログを過去にスクロールしても返信を拾いやすい。
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
    // P3-E of 5b review: input が空文字の Confirming は通常起きない (phone 側で送信前に
    // clearInput → CONFIRMING 遷移は出ないはず) が、念のため防御。空のときは送信プレビュー
    // 行をスキップし、HintLine + 送信/取消 の選択肢のみ表示する。
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
        // ツール名 + description を 1〜2 行で表示。HUD は小さいので説明文は折返し。
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

// P3-B of 5b review: HUD 文字サイズ + 行高を 1 か所に集約。`MESSAGE_FONT_SP` を変更すると
// MessageRow / InputLine / ConfirmBar / PermissionConfirmBar が連動する。
// `LINE_HEIGHT_SP` は MessageRow の fontSize + Arrangement.spacedBy(3.dp) を実機計測した値
// で、`MESSAGE_FONT_SP` の 1.75 倍にあたる。フォント変更時はこの比率も再計測する。
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
