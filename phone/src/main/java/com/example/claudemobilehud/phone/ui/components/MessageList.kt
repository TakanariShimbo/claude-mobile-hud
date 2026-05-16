package com.example.claudemobilehud.phone.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.phone.ui.util.rememberImageBitmapFromPath
import com.example.claudemobilehud.protocol.MessageRole

/**
 * 会話メッセージ一覧。最新が下に追加されると自動で末尾までスクロールする。
 * AD-18 (Compose recomposition 戦略): `messages: List<ChatMessage>` だけを受け取り
 * その他のフィールド変化で recompose されない (`LazyColumn` が `key = { it.id }` で
 * item diff を最小化)。
 */
@Composable
fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) state.animateScrollToItem(messages.size - 1)
    }
    if (messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "メッセージを送って Claude Code を呼び出してください。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(items = messages, key = { it.id }) { msg -> MessageRow(msg) }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val alignment = when (message.role) {
        MessageRole.OUTGOING -> Alignment.End
        MessageRole.INCOMING -> Alignment.Start
        MessageRole.SYSTEM -> Alignment.CenterHorizontally
    }
    val bgColor = when (message.role) {
        MessageRole.OUTGOING -> MaterialTheme.colorScheme.primaryContainer
        MessageRole.INCOMING -> MaterialTheme.colorScheme.surfaceContainerHigh
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
    }
    val fgColor = when (message.role) {
        MessageRole.OUTGOING -> MaterialTheme.colorScheme.onPrimaryContainer
        MessageRole.INCOMING -> MaterialTheme.colorScheme.onSurface
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.onErrorContainer
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        message.chatId?.let {
            Text(
                "chat $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        // P3-D of 4c2 review:
        //  - 旧 `text.isNotBlank() || image == null` だと「text 空 + image 無し」
        //    で空 bubble に "(画像)" だけ出て見える (image を消した古い msg や
        //    SYSTEM 系の空 text などで実際に発生)。
        //  - 画像の bitmap load 失敗時もユーザに何か見せたいので、image があるが
        //    bitmap が null の場合だけ "(画像)" フォールバックを出す。
        val bitmap = message.image?.let { rememberImageBitmapFromPath(it.localPath) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "添付画像",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            if (message.text.isNotBlank()) Spacer(Modifier.height(4.dp))
        }
        val displayText = when {
            message.text.isNotBlank() -> message.text
            message.image != null && bitmap == null -> "(画像)"
            else -> null
        }
        if (displayText != null) {
            Surface(
                color = bgColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = displayText,
                    color = fgColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
