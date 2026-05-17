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

/** docs/03 §3.6.2 / §3.6.2.3: DataStore Preferences で 4 key を持つ。v1.0 は平文 (脅威モデル参照)。 */
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            baseUrl = prefs[KEY_BASE_URL] ?: "",
            token = prefs[KEY_TOKEN] ?: "",
            openAiApiKey = prefs[KEY_OPENAI_API_KEY] ?: "",
            // docs/03 §3.6.2.3: empty-string は null 扱いで FR-PH-54 復元の誤動作回避。
            lastCurrentSessionId = prefs[KEY_LAST_SESSION_ID]?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    suspend fun save(value: Settings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = value.baseUrl
            prefs[KEY_TOKEN] = value.token
            prefs[KEY_OPENAI_API_KEY] = value.openAiApiKey
            // docs/03 §3.6.2.3: empty 系は remove で正規化 (FR-PH-54 復元の誤動作回避)。
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
