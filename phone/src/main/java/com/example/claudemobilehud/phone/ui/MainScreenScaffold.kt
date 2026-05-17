package com.example.claudemobilehud.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.model.PhoneUiState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
import com.example.claudemobilehud.phone.ui.components.ConnectionLine
import com.example.claudemobilehud.phone.ui.components.InputBar
import com.example.claudemobilehud.phone.ui.components.MessageList
import com.example.claudemobilehud.phone.ui.components.SessionDrawer
import kotlinx.coroutines.launch

/**
 * TopBar / Drawer / MessageList / InputBar をまとめる Scaffold (docs/03 §3.5.1.4)。
 * dialog 表示と LaunchedEffect 群は [MainScreenDialogs] / [MainScreenEffects] に分離。
 * imePadding + adjustNothing のペア / photo picker / MessageList の recompose 最適化 /
 * TopAppBar title fallback は §3.5.1.4 を参照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenScaffold(
    ui: PhoneUiState,
    settings: Settings,
    connectivity: ConnectivityState,
    transcriptionState: TranscriptionClient.State,
    inputText: String,
    dialogState: MainScreenDialogState,
    viewModel: ChatViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // docs/03 §3.5.2.3: Uri 取込は Repository に集約 (error 翻訳の UI 漏れ防止)。
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.attachImageFromUri(uri)
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startTranscriptionPhoneMic()
        } else {
            scope.launch { dialogState.snackbar.showSnackbar("マイクの権限が必要です") }
        }
    }

    val micActive = transcriptionState !is TranscriptionClient.State.Idle &&
        transcriptionState !is TranscriptionClient.State.Error
    val micAvailable = settings.openAiApiKey.isNotBlank()
    val configured = settings.isConfigured

    ModalNavigationDrawer(
        drawerState = dialogState.drawer,
        drawerContent = {
            SessionDrawer(
                sessions = ui.sessions,
                currentSessionId = ui.currentSessionId,
                onSelect = {
                    viewModel.selectSession(it)
                    scope.launch { dialogState.drawer.close() }
                },
                onDeleteRequest = { dialogState.pendingDeleteSessionId = it },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { dialogState.drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "セッション一覧")
                        }
                    },
                    title = {
                        Column {
                            Text(currentSessionTitle(ui.currentSessionId, ui.sessions))
                            ConnectionLine(connectivity)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.reconnect() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "再接続")
                        }
                        IconButton(onClick = { dialogState.showGlass = true }) {
                            Icon(Icons.Default.Visibility, contentDescription = "グラス接続")
                        }
                        IconButton(onClick = { dialogState.showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "設定")
                        }
                        IconButton(onClick = { dialogState.showExit = true }) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "終了")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(dialogState.snackbar) },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            // docs/03 §3.5.1.4: imePadding と Manifest の windowSoftInputMode=adjustNothing は
            // ペア (片方だけ外すと入力欄下の隙間 regression)。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // docs/03 §3.5.1.4: messages のみを引数化 + LazyColumn key で item-level recompose 最小化。
                MessageList(
                    messages = ui.messages,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                HorizontalDivider()
                InputBar(
                    value = inputText,
                    onValueChange = { viewModel.updateInputText(it) },
                    onSend = { viewModel.send(inputText) }, // docs/03 §3.5.2.2: click-time snapshot
                    onPickImage = {
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onClearAttachment = { viewModel.clearAttachedImage() },
                    pendingImage = ui.attachedImage,
                    onToggleMic = {
                        if (micActive) {
                            viewModel.stopTranscription()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.startTranscriptionPhoneMic()
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    micAvailable = micAvailable,
                    micActive = micActive,
                    enabled = configured,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun currentSessionTitle(
    currentSessionId: String?,
    sessions: List<com.example.claudemobilehud.phone.data.model.SessionSummary>,
): String {
    val id = currentSessionId ?: return "Claude Mobile HUD"
    val summary = sessions.firstOrNull { it.id == id }
    // docs/03 §3.5.1.4 (P3-B): SessionSummary.label を優先、一覧に居ないときだけ id.take(8) fallback。
    val label = summary?.label ?: id.take(8)
    val count = summary?.messageCount ?: 0
    return "session $label  ·  $count msg"
}
