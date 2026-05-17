package com.example.claudemobilehud.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** docs/03 §3.5.1.6: AD-18 の 3 sibling composable + 1 state holder に分配する最上位 composable。 */
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
