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

/**
 * Phase 3 §3.5.1: dialog の if 分岐をここに集約。Scaffold (描画ツリー本体) と
 * 切り離すことで `MainScreenScaffold` の recomposition と dialog 表示状態の
 * recomposition が独立する。
 */
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

    // 現在見ているセッションの pending だけを in-app ダイアログで表示。別 session の
    // permission は通知シェードの Allow/Deny で処理できるので画面遷移を奪わない (glass 側
    // のフィルタと対称)。filter は Repository.combine で計算済み (`ui.pendingForCurrent`)、
    // UI はそれを読むだけ (P1-6 of AC-05)。
    val currentPending = ui.pendingForCurrent
    val pending = currentPending.firstOrNull()
    if (pending != null) {
        // P1-B of 4c2 review: `respondPermission` は suspend で網羅に時間がかかり、
        // 完了して `_pendingPermissions` が更新されるまで dialog は閉じない。連打防止に
        // 当該 request_id ごとに `responded` ゲートを持つ。ゲートは pending.requestId に
        // keying してあるので、別 request が来たら自動でリセット。
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
