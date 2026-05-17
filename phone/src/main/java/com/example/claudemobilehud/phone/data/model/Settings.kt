package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable

/** docs/03 §3.6.5.3: Hub 接続 + OpenAI key + 再起動後 session 復元 (FR-PH-54)。SettingsStore で永続化。 */
@Immutable
data class Settings(
    val baseUrl: String = "",
    val token: String = "",
    val openAiApiKey: String = "",
    val lastCurrentSessionId: String? = null,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()
}
