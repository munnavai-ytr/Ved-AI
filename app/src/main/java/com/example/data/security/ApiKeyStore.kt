package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.BuildConfig
import com.example.data.model.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Indicates where the effective API key for a provider originates.
 */
enum class KeySource {
  OVERRIDE,
  BUILD_CONFIG,
  MISSING,
}

/**
 * Manages optional local API key overrides encrypted via Android Keystore /
 * EncryptedSharedPreferences, falling back to build-time BuildConfig secrets.
 */
open class ApiKeyStore(private val context: Context) {

  private val prefsLock = Any()
  @Volatile private var cachedPrefs: SharedPreferences? = null

  private val _keyVersion = MutableStateFlow(0)
  val keyVersion: StateFlow<Int> = _keyVersion.asStateFlow()

  private fun getEncryptedPrefs(): SharedPreferences {
    cachedPrefs?.let { return it }
    return synchronized(prefsLock) {
      cachedPrefs?.let { return it }
      val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
      EncryptedSharedPreferences.create(
          PREFS_FILE,
          masterKeyAlias,
          context.applicationContext,
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        .also { cachedPrefs = it }
    }
  }

  open suspend fun getEffectiveKey(provider: AiProvider): String =
    withContext(Dispatchers.IO) {
      val override = getEncryptedPrefs().getString(prefKeyFor(provider), null)?.trim().orEmpty()
      if (override.isNotEmpty()) {
        override
      } else {
        buildConfigKeyFor(provider)
      }
    }

  suspend fun getKeySource(provider: AiProvider): KeySource =
    withContext(Dispatchers.IO) {
      val override = getEncryptedPrefs().getString(prefKeyFor(provider), null)?.trim().orEmpty()
      when {
        override.isNotEmpty() -> KeySource.OVERRIDE
        buildConfigKeyFor(provider).isNotEmpty() -> KeySource.BUILD_CONFIG
        else -> KeySource.MISSING
      }
    }

  suspend fun setOverrideKey(provider: AiProvider, rawKey: String) =
    withContext(Dispatchers.IO) {
      val sanitized = rawKey.trim()
      getEncryptedPrefs().edit().apply {
        if (sanitized.isEmpty()) {
          remove(prefKeyFor(provider))
        } else {
          putString(prefKeyFor(provider), sanitized)
        }
        apply()
      }
      _keyVersion.value += 1
    }

  suspend fun clearOverrideKey(provider: AiProvider) =
    withContext(Dispatchers.IO) {
      getEncryptedPrefs().edit().remove(prefKeyFor(provider)).apply()
      _keyVersion.value += 1
    }

  private fun prefKeyFor(provider: AiProvider): String =
    when (provider) {
      AiProvider.GEMINI -> KEY_GEMINI_OVERRIDE
      AiProvider.OPENROUTER -> KEY_OPENROUTER_OVERRIDE
    }

  private fun buildConfigKeyFor(provider: AiProvider): String {
    val raw =
      when (provider) {
        AiProvider.GEMINI -> BuildConfig.GEMINI_API_KEY.trim()
        AiProvider.OPENROUTER -> BuildConfig.OPENROUTER_API_KEY.trim()
      }
    return if (raw == PLACEHOLDER_GEMINI || raw == PLACEHOLDER_OPENROUTER) "" else raw
  }

  private companion object {
    const val PREFS_FILE = "ved_encrypted_keys"
    const val KEY_GEMINI_OVERRIDE = "override_gemini_api_key"
    const val KEY_OPENROUTER_OVERRIDE = "override_openrouter_api_key"
    const val PLACEHOLDER_GEMINI = "MY_GEMINI_API_KEY"
    const val PLACEHOLDER_OPENROUTER = "MY_OPENROUTER_API_KEY"
  }
}
