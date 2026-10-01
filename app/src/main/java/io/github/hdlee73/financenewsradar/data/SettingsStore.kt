package io.github.hdlee73.financenewsradar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.hdlee73.financenewsradar.model.AppSettings
import io.github.hdlee73.financenewsradar.model.NaverApiType
import io.github.hdlee73.financenewsradar.model.NaverCredentials
import io.github.hdlee73.financenewsradar.model.NewsProviderType
import io.github.hdlee73.financenewsradar.model.OutletScope
import io.github.hdlee73.financenewsradar.model.TimeRange
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("news_radar_settings", Context.MODE_PRIVATE)
    private val cipher = CredentialCipher()

    fun loadSettings(): AppSettings {
        val keywords = preferences.getString(KEYWORDS, null)
            ?.split(KEYWORD_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(5)
            .orEmpty()
            .ifEmpty { AppSettings.DEFAULT_KEYWORDS }
        return AppSettings(
            keywords = keywords,
            provider = enumValue(preferences.getString(PROVIDER, null), NewsProviderType.COMBINED),
            outletScope = enumValue(preferences.getString(SCOPE, null), OutletScope.ALL),
            timeRange = enumValue(preferences.getString(TIME_RANGE, null), TimeRange.WEEK)
        )
    }

    fun saveSettings(settings: AppSettings) {
        val cleanKeywords = settings.keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(5)
        preferences.edit()
            .putString(KEYWORDS, cleanKeywords.joinToString(KEYWORD_SEPARATOR))
            .putString(PROVIDER, settings.provider.name)
            .putString(SCOPE, settings.outletScope.name)
            .putString(TIME_RANGE, settings.timeRange.name)
            .apply()
    }

    fun loadCredentials(): NaverCredentials = NaverCredentials(
        clientId = decrypt(preferences.getString(NAVER_ID, null)),
        clientSecret = decrypt(preferences.getString(NAVER_SECRET, null)),
        apiType = enumValue(preferences.getString(NAVER_API_TYPE, null),
            if (preferences.getString(NAVER_ID, null).isNullOrBlank()) NaverApiType.API_HUB else NaverApiType.DEVELOPERS)
    )

    fun saveCredentials(credentials: NaverCredentials) {
        preferences.edit()
            .putString(NAVER_ID, encrypt(credentials.clientId.trim()))
            .putString(NAVER_SECRET, encrypt(credentials.clientSecret.trim()))
            .putString(NAVER_API_TYPE, credentials.apiType.name)
            .apply()
    }

    fun bookmarks(): Set<String> = preferences.getStringSet(BOOKMARKS, emptySet()).orEmpty().toSet()

    fun toggleBookmark(link: String): Boolean {
        val current = bookmarks().toMutableSet()
        val nowBookmarked = if (link in current) {
            current.remove(link)
            false
        } else {
            current.add(link)
            true
        }
        preferences.edit().putStringSet(BOOKMARKS, current).apply()
        return nowBookmarked
    }

    private fun encrypt(value: String): String = if (value.isBlank()) "" else cipher.encrypt(value)
    private fun decrypt(value: String?): String = if (value.isNullOrBlank()) "" else runCatching { cipher.decrypt(value) }.getOrDefault("")

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    companion object {
        private const val KEYWORDS = "keywords"
        private const val PROVIDER = "provider"
        private const val SCOPE = "scope"
        private const val TIME_RANGE = "time_range"
        private const val NAVER_API_TYPE = "naver_api_type"
        private const val NAVER_ID = "naver_client_id"
        private const val NAVER_SECRET = "naver_client_secret"
        private const val BOOKMARKS = "bookmarks"
        private const val KEYWORD_SEPARATOR = "\u001F"
    }
}

private class CredentialCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > IV_SIZE)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val encrypted = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "finance_news_radar_naver_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
