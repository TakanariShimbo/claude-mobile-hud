package com.example.claudemobilehud.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.claudemobilehud.phone.data.model.ConnectivityState

/** docs/03 §3.5.1.12: 5 状態を色 + label で 1 行表示。AuthFailed は Failed と同色 (error)。 */
@Composable
fun ConnectionLine(status: ConnectivityState) {
    val (label, color) = when (status) {
        ConnectivityState.Idle -> "未設定" to MaterialTheme.colorScheme.outline
        ConnectivityState.Connecting -> "接続中..." to MaterialTheme.colorScheme.tertiary
        ConnectivityState.Open -> "接続済み" to MaterialTheme.colorScheme.primary
        is ConnectivityState.Failed -> "失敗: ${status.reason}" to MaterialTheme.colorScheme.error
        ConnectivityState.AuthFailed -> "token 無効" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
