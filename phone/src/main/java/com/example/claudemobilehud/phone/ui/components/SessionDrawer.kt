package com.example.claudemobilehud.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.model.SessionSummary

@Composable
fun SessionDrawer(
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("セッション", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${sessions.size} 件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        HorizontalDivider()
        if (sessions.isEmpty()) {
            Text(
                "履歴なし。メッセージを送るとここに溜まります。",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(items = sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == currentSessionId,
                    onSelect = onSelect,
                    onDeleteRequest = onDeleteRequest,
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    NavigationDrawerItem(
        selected = selected,
        onClick = { onSelect(session.id) },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.isActive) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(50),
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // P3-B of 4c2 review: SessionSummary.label を直接使う (SessionStore で
                // id.take(8) として既に計算済み)。Glass 向け wire payload と
                // 同じ値で UI を描画することで「Phone と Glass で見えるラベルが違う」
                // ズレを防ぐ。
                Text(
                    "${session.label}  ·  ${session.messageCount} msg",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        badge = {
            IconButton(onClick = { onDeleteRequest(session.id) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "履歴削除",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        },
        colors = NavigationDrawerItemDefaults.colors(),
    )
}
