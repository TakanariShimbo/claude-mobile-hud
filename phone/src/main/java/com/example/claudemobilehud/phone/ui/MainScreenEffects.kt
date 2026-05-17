package com.example.claudemobilehud.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.error.TransientError
import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
import com.example.claudemobilehud.protocol.error.SharedWireError

/**
 * docs/03 §3.5.1.7: LaunchedEffect 3 経路 を集約:
 *  (1) connectivity → settings 自動表示 (P1-A, Idle / AuthFailed)
 *  (2) transcription Error → snackbar
 *  (3) Repository.errors (transient) → snackbar
 */
@Composable
fun MainScreenEffects(
    connectivity: ConnectivityState,
    transcriptionState: TranscriptionClient.State,
    dialogState: MainScreenDialogState,
    viewModel: ChatViewModel,
) {
    val snackbar = dialogState.snackbar

    // docs/03 §3.5.1.7: connectivity を key にすることで起動後の状態変化も再評価 (P1-A)。
    LaunchedEffect(connectivity) {
        when (connectivity) {
            ConnectivityState.Idle, ConnectivityState.AuthFailed -> dialogState.showSettings = true
            else -> Unit
        }
    }

    LaunchedEffect(transcriptionState) {
        if (transcriptionState is TranscriptionClient.State.Error) {
            snackbar.showSnackbar("音声入力エラー: ${transcriptionState.message}")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { err ->
            snackbar.showSnackbar(err.toUserMessage())
        }
    }
}

// docs/03 §3.5.1.7: snackbar 1 行 message 翻訳。詳細 UI 表現は §3.7 mapToPresentation。
private fun TransientError.toUserMessage(): String = when (this) {
    is TransientError.Shared -> when (val w = error) {
        is SharedWireError.Connection.AuthFailed -> "token が無効です。設定を確認してください。"
        is SharedWireError.Connection.NotConfigured -> "先に設定を開いてください"
        is SharedWireError.Connection.ConnectFailed -> "Hub に接続できません: ${w.causeMessage ?: "(不明)"}"
        is SharedWireError.Connection.ServerError -> "Hub からエラー応答 (HTTP ${w.httpCode})"
        is SharedWireError.Permission.AlreadyVerdicted -> "permission は既に判定済みです"
        is SharedWireError.Permission.Aborted -> "Claude 側で取消されました"
        is SharedWireError.Permission.Unknown -> "未知の permission request id"
    }
    is TransientError.Phone -> when (val w = error) {
        is PhoneWireError.Send.ImageTooLarge -> "画像が大きすぎます (${w.actualBytes / 1024} KB)"
        is PhoneWireError.Send.SessionNotActive -> "対象 session は終了しています"
        is PhoneWireError.Send.Cancelled -> "送信を取消しました"
        is PhoneWireError.Transcription.ApiKeyMissing -> "OpenAI API key が未設定"
        is PhoneWireError.Transcription.ApiKeyInvalid -> "OpenAI API key が無効"
        is PhoneWireError.Transcription.MicPermissionDenied -> "マイクの権限が必要です"
        is PhoneWireError.Transcription.NetworkFailed -> "音声 API 通信失敗"
        is PhoneWireError.Transcription.ServiceError -> "音声 API エラー: ${w.message}"
        is PhoneWireError.Glass.TokenMissing -> "Glass token が未設定"
        is PhoneWireError.Glass.HiRokidNotInstalled -> "Hi Rokid アプリが未インストール"
        is PhoneWireError.Glass.CxrConnectFailed -> "Glass 接続失敗"
        is PhoneWireError.Glass.BtScoUnavailable -> "内蔵マイクを使用中 (BT mic 取得不可)"
    }
}
