package com.example.claudemobilehud.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.data.ChannelEvent
import com.example.claudemobilehud.phone.data.ChannelRepository
import com.example.claudemobilehud.phone.data.error.TransientError
import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.data.model.PhoneUiState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
import com.example.claudemobilehud.protocol.PermissionDecision
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI 層と Repository をつなぐ薄い ViewModel。Phase 3 §3.5.2。
 *
 * - すべての suspend action (`send` / `selectSession` / 等) は `viewModelScope` で
 *   `launch` する。UI 側はコールバックを fire-and-forget で呼べる。
 * - `repository` は `PhoneApplication.container` から取得。AppContainer が初期化
 *   される前に ViewModel が作られる経路はない (MainActivity が Application 経由で
 *   起動するため)。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChannelRepository =
        (application as PhoneApplication).container.repository

    val uiState: StateFlow<PhoneUiState> = repository.uiState
    val settings: StateFlow<Settings> = repository.settings
    val connectivity: StateFlow<ConnectivityState> = repository.connectivity
    val inputText: StateFlow<String> = repository.inputText
    val transcriptionState: StateFlow<TranscriptionClient.State> =
        repository.input.transcription.state
    val errors: SharedFlow<TransientError> = repository.errors
    val events: SharedFlow<ChannelEvent> = repository.events

    fun saveSettings(value: Settings) {
        viewModelScope.launch { repository.saveSettings(value) }
    }

    fun reconnect() {
        repository.reconnect()
    }

    fun selectSession(sessionId: String) {
        viewModelScope.launch { repository.selectSession(sessionId) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }

    fun respondPermission(requestId: String, decision: PermissionDecision) {
        viewModelScope.launch { repository.respondPermission(requestId, decision) }
    }

    fun attachImage(image: ImageAttachment) = repository.attachImage(image)

    /**
     * P2-E of 4c2 review: Uri 取込を Repository に集約。失敗は Repository._errors
     * 経由で MainScreenEffects.toUserMessage の localized snackbar 経路を通る。
     */
    fun attachImageFromUri(uri: android.net.Uri) {
        viewModelScope.launch { repository.attachImageFromUri(uri) }
    }

    fun clearAttachedImage() = repository.clearAttachedImage()

    fun updateInputText(text: String) = repository.updateInputText(text)

    /**
     * P1-C of 4c2 review: 送信テキストを呼び出し側で snapshot 化して引数で渡す。
     * `repository.send(inputText.value)` 形にすると IME composition の遅延入力や
     * transcription の partial 更新が click → suspend 起動の隙間で割り込み、
     * 想定と違うテキストが送出される race があった。
     */
    fun send(text: String) {
        viewModelScope.launch { repository.send(text) }
    }

    fun startTranscriptionPhoneMic() = repository.startTranscriptionPhoneMic()
    fun startTranscriptionFromGlass() = repository.startTranscriptionFromGlass()
    fun stopTranscription() = repository.stopTranscription()
}
