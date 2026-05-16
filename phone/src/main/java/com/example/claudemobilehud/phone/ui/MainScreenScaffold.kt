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
 * Phase 3 §3.5.1: TopBar / Drawer / MessageList / InputBar をまとめる。
 * dialog 表示と LaunchedEffect 群は [MainScreenDialogs] / [MainScreenEffects] に分離。
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

    // P2-E of 4c2 review: Uri 取込は Repository に集約。エラーは Repository._errors
     // → MainScreenEffects.toUserMessage 経由で localized snackbar が出る。ここで
    // runCatching + 生 message を snackbar に出すと、ImageTooLarge の表示が UI 経路と
    // 一貫しなかった。
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
            // IME 追従は `.imePadding()` が担当する。Activity 側で
            // `windowSoftInputMode="adjustNothing"` (AndroidManifest.xml) を設定して
            // system の window resize を黙らせているので、Android 15+ の `adjustResize`
            // 強制と `.imePadding()` の二重補正は発生しない (= 入力欄下に隙間が出ない)。
            // この 2 つはペアで動く設計なので、片方だけ外すと regression する。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // P2-A of 4c2 review: parent (Scaffold) は ui の他フィールド変化でも
                // recompose されるが、MessageList は `messages: List<ChatMessage>` だけを
                // 受け取り、LazyColumn の `key = { it.id }` で item-level recompose を
                // 最小化する。`@Immutable` PhoneUiState (P3-A) + reference-equal messages
                // で Compose の skip も働く。
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
                    onSend = { viewModel.send(inputText) }, // P1-C: snapshot at click
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
    // P3-B of 4c2 review: TopAppBar も SessionSummary.label を直接使う (SessionDrawer
    // と同じ値)。session が一覧に居ない (= UNKNOWN_SESSION_ID で active 化前) の
    // ケースだけ shortSessionLabel に fallback する。
    val label = summary?.label ?: id.take(8)
    val count = summary?.messageCount ?: 0
    return "session $label  ·  $count msg"
}
