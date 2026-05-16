package com.example.claudemobilehud.phone.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.claudemobilehud.phone.data.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Phone 設定の永続化。Phase 3 §3.6.2。Preferences DataStore。
 *
 * 鍵:
 *   base_url, token, openai_api_key, last_current_session_id
 *
 * NOTE: token は機微情報だが v1.0 では平文 Preferences。`androidx.security.crypto` への
 * 移行は将来 (key rotation の運用設計と合わせて)。NFR-20 LAN/Tailscale 前提の脅威モデル下では
 * Phone 自体が compromised 状況を主敵にはしていない。
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            baseUrl = prefs[KEY_BASE_URL] ?: "",
            token = prefs[KEY_TOKEN] ?: "",
            openAiApiKey = prefs[KEY_OPENAI_API_KEY] ?: "",
            lastCurrentSessionId = prefs[KEY_LAST_SESSION_ID]?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    suspend fun save(value: Settings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = value.baseUrl
            prefs[KEY_TOKEN] = value.token
            prefs[KEY_OPENAI_API_KEY] = value.openAiApiKey
            if (value.lastCurrentSessionId.isNullOrEmpty()) {
                prefs.remove(KEY_LAST_SESSION_ID)
            } else {
                prefs[KEY_LAST_SESSION_ID] = value.lastCurrentSessionId
            }
        }
    }

    suspend fun saveLastCurrentSessionId(sessionId: String?) {
        context.dataStore.edit { prefs ->
            if (sessionId.isNullOrEmpty()) prefs.remove(KEY_LAST_SESSION_ID)
            else prefs[KEY_LAST_SESSION_ID] = sessionId
        }
    }

    companion object {
        private const val PREFS_NAME = "claude_mobile_hud_settings"
        private val Context.dataStore by preferencesDataStore(name = PREFS_NAME)

        private val KEY_BASE_URL: Preferences.Key<String> = stringPreferencesKey("base_url")
        private val KEY_TOKEN: Preferences.Key<String> = stringPreferencesKey("token")
        private val KEY_OPENAI_API_KEY: Preferences.Key<String> =
            stringPreferencesKey("openai_api_key")
        private val KEY_LAST_SESSION_ID: Preferences.Key<String> =
            stringPreferencesKey("last_current_session_id")
    }
}
