package com.example.claudemobilehud.phone.service

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CXR-L pairing token の secure storage (docs/03 §3.6.2.1)。Settings DataStore と分ける理由、
 * `prefs()` の lazy cache (P3-1)、`load` の例外吸収、StateFlow 同期更新は §3.6.2.2 を参照。
 */
object TokenStore {
    private const val PREFS_NAME = "claude_mhud_secure_prefs"
    private const val KEY_CXR_TOKEN = "cxr_token"
    private val log = StructuredLog("channel.token")

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    @Volatile
    private var cached: android.content.SharedPreferences? = null

    private fun prefs(context: Context): android.content.SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val built = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = built
            return built
        }
    }

    fun load(context: Context) {
        _token.value = try {
            prefs(context).getString(KEY_CXR_TOKEN, null)
        } catch (e: Throwable) {
            // docs/03 §3.6.2.2: master key 異常 / 鍵ローテ後のリストア等で起動を止めない。
            log.warn("token_load_failed", e)
            null
        }
    }

    fun save(context: Context, value: String) {
        prefs(context).edit { putString(KEY_CXR_TOKEN, value) }
        _token.value = value
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_CXR_TOKEN) }
        _token.value = null
    }
}
