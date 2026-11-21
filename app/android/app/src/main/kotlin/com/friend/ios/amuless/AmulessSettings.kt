package com.friend.ios.amuless

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User settings for transcription and Memoria.
 *
 * URLs live in plain preferences. The Memoria API token is kept in EncryptedSharedPreferences
 * (Android Keystore) and is never logged or sent back to Dart.
 */
object AmulessSettings {
    private const val TAG = "Amuless.Settings"
    private const val PLAIN = "amuless_settings"
    private const val SECURE = "amuless_secure"
    private const val K_WHISPER_URL = "whisper_url"
    private const val K_WHISPER_MODEL = "whisper_model"
    private const val K_MEMORIA_URL = "memoria_url"
    private const val K_TOKEN = "memoria_token"

    const val DEFAULT_MODEL = "mlx-community/whisper-large-v3-turbo-asr-fp16"

    private fun plain(context: Context) = context.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)

    private fun secure(context: Context) = EncryptedSharedPreferences.create(
        context,
        SECURE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** e.g. http://host:8000/v1 (no trailing slash). Empty when not set. */
    fun whisperUrl(context: Context): String = plain(context).getString(K_WHISPER_URL, "").orEmpty().trim().trimEnd('/')
    fun whisperModel(context: Context): String =
        plain(context).getString(K_WHISPER_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }

    /** e.g. https://host:8443 (no trailing slash). Empty when not set. */
    fun memoriaUrl(context: Context): String = plain(context).getString(K_MEMORIA_URL, "").orEmpty().trim().trimEnd('/')

    fun token(context: Context): String? = try {
        secure(context).getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read token: ${e.javaClass.simpleName}")
        null
    }

    fun hasToken(context: Context): Boolean = token(context) != null

    /** [token]: null keeps the stored one, "" clears it. */
    fun save(context: Context, whisperUrl: String?, whisperModel: String?, memoriaUrl: String?, token: String?) {
        val e = plain(context).edit()
        whisperUrl?.let { e.putString(K_WHISPER_URL, it.trim().trimEnd('/')) }
        whisperModel?.let { e.putString(K_WHISPER_MODEL, it.trim()) }
        memoriaUrl?.let { e.putString(K_MEMORIA_URL, it.trim().trimEnd('/')) }
        e.apply()
        if (token != null) {
            val s = secure(context).edit()
            if (token.isBlank()) s.remove(K_TOKEN) else s.putString(K_TOKEN, token.trim())
            s.apply()
        }
    }

    fun isConfigured(context: Context): Boolean =
        whisperUrl(context).isNotEmpty() && memoriaUrl(context).isNotEmpty() && hasToken(context)
}
