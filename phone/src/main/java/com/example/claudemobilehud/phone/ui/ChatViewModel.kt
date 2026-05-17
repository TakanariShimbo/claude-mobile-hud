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
 * UI 層と Repository をつなぐ薄い ViewModel (docs/03 §3.5.2 / §3.5.2.1)。`send` の snapshot 引数
 * (§3.5.2.2 P1-C) と `attachImageFromUri` の Repository 集約 (§3.5.2.3 P2-E) を参照。
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

    fun attachImageFromUri(uri: android.net.Uri) {
        viewModelScope.launch { repository.attachImageFromUri(uri) }
    }

    fun clearAttachedImage() = repository.clearAttachedImage()

    fun updateInputText(text: String) = repository.updateInputText(text)

    fun send(text: String) {
        viewModelScope.launch { repository.send(text) }
    }

    fun startTranscriptionPhoneMic() = repository.startTranscriptionPhoneMic()
    fun startTranscriptionFromGlass() = repository.startTranscriptionFromGlass()
    fun stopTranscription() = repository.stopTranscription()
}
