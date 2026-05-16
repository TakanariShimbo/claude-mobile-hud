package com.example.claudemobilehud.phone.data.model

/**
 * Phone 側設定。SettingsStore (DataStore Preferences) で永続化。Phase 3 §3.6.2。
 *
 * - `baseUrl`: Hub の HTTP origin (例: "http://192.168.1.10:8788")
 * - `token`: X-Token (NFR-20)
 * - `openAiApiKey`: 音声入力 (OpenAI Realtime API) 用
 * - `lastCurrentSessionId`: FR-PH-54 で 再起動後の current session 復元用
 */
data class Settings(
    val baseUrl: String = "",
    val token: String = "",
    val openAiApiKey: String = "",
    val lastCurrentSessionId: String? = null,
) {
    /** Hub に繋ぐのに最低限必要な情報が揃っているか。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()
}
