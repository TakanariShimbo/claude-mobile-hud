package com.example.claudemobilehud.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Phase 3 §3.5.1 の最上位 composable。
 *
 * POC では 275 行の 1 composable に Scaffold / Dialogs / Effects がすべて入っていた。
 * Rev 2 の AD-18 (Compose recomposition 戦略) で 3 + 1 (state holder) に分解。
 * 各サブ composable は自分が必要とする state だけ受け取り、他フィールド変化で
 * 再描画されない。
 */
@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()

    val dialogState = rememberMainScreenDialogState()

    MainScreenScaffold(
        ui = ui,
        settings = settings,
        connectivity = connectivity,
        transcriptionState = transcriptionState,
        inputText = inputText,
        dialogState = dialogState,
        viewModel = viewModel,
    )
    MainScreenDialogs(
        ui = ui,
        settings = settings,
        dialogState = dialogState,
        viewModel = viewModel,
    )
    MainScreenEffects(
        connectivity = connectivity,
        transcriptionState = transcriptionState,
        dialogState = dialogState,
        viewModel = viewModel,
    )
}
