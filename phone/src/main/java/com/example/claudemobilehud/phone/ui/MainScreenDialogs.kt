package com.example.claudemobilehud.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.claudemobilehud.phone.data.model.PhoneUiState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.ui.dialogs.DeleteSessionDialog
import com.example.claudemobilehud.phone.ui.dialogs.ExitDialog
import com.example.claudemobilehud.phone.ui.dialogs.GlassDialog
import com.example.claudemobilehud.phone.ui.dialogs.PermissionDialog
import com.example.claudemobilehud.phone.ui.dialogs.SettingsDialog
import com.example.claudemobilehud.phone.ui.util.shortSessionLabel
import com.example.claudemobilehud.protocol.PermissionDecision

/** docs/03 §3.5.1.9: dialog 分岐集約 (Scaffold と分離して recompose 粒度を切る)。 */
@Composable
fun MainScreenDialogs(
    ui: PhoneUiState,
    settings: Settings,
    dialogState: MainScreenDialogState,
    viewModel: ChatViewModel,
) {
    if (dialogState.showSettings) {
        SettingsDialog(
            initial = settings,
            onDismiss = { dialogState.showSettings = false },
            onSave = {
                viewModel.saveSettings(it)
                dialogState.showSettings = false
            },
        )
    }

    if (dialogState.showGlass) {
        GlassDialog(onDismiss = { dialogState.showGlass = false })
    }

    if (dialogState.showExit) {
        ExitDialog(onDismiss = { dialogState.showExit = false })
    }

    // docs/03 §3.5.1.9: pendingForCurrent (Repository combine 済) のみ表示 (P1-6 AC-05)。
    val currentPending = ui.pendingForCurrent
    val pending = currentPending.firstOrNull()
    if (pending != null) {
        // docs/03 §3.5.1.9: request_id keyed responded gate で連打 → 重複 verdict 防止 (P1-B)。
        var responded by remember(pending.requestId) { mutableStateOf(false) }
        PermissionDialog(
            request = pending,
            remainingCount = (currentPending.size - 1).coerceAtLeast(0),
            onAllow = {
                if (!responded) {
                    responded = true
                    viewModel.respondPermission(pending.requestId, PermissionDecision.ALLOW)
                }
            },
            onDeny = {
                if (!responded) {
                    responded = true
                    viewModel.respondPermission(pending.requestId, PermissionDecision.DENY)
                }
            },
        )
    }

    dialogState.pendingDeleteSessionId?.let { idToDelete ->
        val target = ui.sessions.firstOrNull { it.id == idToDelete }
        DeleteSessionDialog(
            sessionLabel = shortSessionLabel(idToDelete),
            messageCount = target?.messageCount ?: 0,
            isActive = target?.isActive == true,
            onDismiss = { dialogState.pendingDeleteSessionId = null },
            onConfirm = {
                viewModel.deleteSession(idToDelete)
                dialogState.pendingDeleteSessionId = null
            },
        )
    }
}
