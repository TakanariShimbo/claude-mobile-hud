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
 * CXR-L pairing token を EncryptedSharedPreferences で保持する secure storage。
 *
 * 設計書 §3.4 / §3.6 系の前提:
 *   - UI から書き換え可能 (QR ペア時に save)。
 *   - 4c の GlassConnectionService が起動時に最新値を読み CXR-L `connect` に使う。
 *
 * Settings DataStore (baseUrl / token / openAiApiKey) と分けているのは:
 *   - CXR-L token は Rokid SDK にとっての credential であり Hub の HTTP token とは別物。
 *   - EncryptedSharedPreferences で at-rest 暗号化したい (NFR-20 と同等扱い)。
 *
 * lazy init を取らないのは、`load(context)` を Application.onCreate で 1 回だけ
 * 同期実行して memory cache に載せれば、以降の read は flow 経由で済むため。
 */
object TokenStore {
    private const val PREFS_NAME = "claude_mhud_secure_prefs"
    private const val KEY_CXR_TOKEN = "cxr_token"
    // P3-5: 旧 "channel.glass" は glass relay 層と被るため secure storage 用に分離。
    private val log = StructuredLog("channel.token")

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    // P3-1: EncryptedSharedPreferences の build は MasterKey 派生 (KMS) を毎回叩くため
    // ms オーダーで重い。Application scope の単一インスタンスをキャッシュする。
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
            // master key 異常 / 暗号鍵ローテ後のリストアなど: 起動を止めないため null fallback。
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
