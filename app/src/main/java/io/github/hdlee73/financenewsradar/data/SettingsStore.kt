package io.github.hdlee73.financenewsradar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.AppSettings
import io.github.hdlee73.financenewsradar.model.NaverApiType
import io.github.hdlee73.financenewsradar.model.NaverCredentials
import io.github.hdlee73.financenewsradar.model.NewsProviderType
import io.github.hdlee73.financenewsradar.model.OutletScope
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import io.github.hdlee73.financenewsradar.model.TimeRange
import io.github.hdlee73.financenewsradar.model.UsefulLink
import java.security.KeyStore
import java.time.LocalDate
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
            ?.take(10)
            .orEmpty()
            .ifEmpty { AppSettings.DEFAULT_KEYWORDS }
        return AppSettings(
            keywords = keywords,
            provider = enumValue(preferences.getString(PROVIDER, null), NewsProviderType.NAVER),
            outletScope = enumValue(preferences.getString(SCOPE, null), OutletScope.ALL),
            timeRange = enumValue(preferences.getString(TIME_RANGE, null), TimeRange.WEEK)
        )
    }

    fun saveSettings(settings: AppSettings) {
        val cleanKeywords = settings.keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10)
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

    /** 사용자가 편집하는 금융 사이트 링크. 저장된 적이 없으면 기본 10곳. 비워서 저장하면 빈 목록을 유지한다. */
    fun loadLinks(): List<UsefulLink> {
        val raw = preferences.getString(LINKS, null) ?: return UsefulLink.DEFAULTS
        var links = raw.split(RECORD_SEPARATOR).mapNotNull { record ->
            val parts = record.split(KEYWORD_SEPARATOR)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) UsefulLink(parts[0], parts[1]) else null
        }
        if (!preferences.getBoolean(LINKS_MIGRATED_V051, false)) {
            // 채권정보센터 주소 변경 반영 + 새 기본 사이트(DART·파인·KRX)를 금융위원회 아래에 한 번만 추가.
            links = links.map {
                if (it.url.contains("bond.kofia.or.kr")) it.copy(url = "https://www.kofiabond.or.kr") else it
            }
            val wanted = UsefulLink.DEFAULTS.filter { d -> listOf("dart.fss.or.kr", "fine.fss.or.kr", "krx.or.kr").any { d.url.contains(it) } }
            val missing = wanted.filter { w -> links.none { it.url.contains(w.url.removePrefix("https://www.").removePrefix("https://")) } }
            val at = links.indexOfFirst { it.url.contains("fsc.go.kr") }.let { if (it >= 0) it + 1 else links.size }
            links = links.take(at) + missing + links.drop(at)
            preferences.edit().putBoolean(LINKS_MIGRATED_V051, true).apply()
            saveLinks(links)
        }
        return links
    }

    fun saveLinks(links: List<UsefulLink>) {
        preferences.edit()
            .putString(LINKS, links.joinToString(RECORD_SEPARATOR) { "${it.name}$KEYWORD_SEPARATOR${it.url}" })
            .apply()
    }

    /** 기관별로 이미 본 자료 링크. 한 번도 저장한 적이 없으면 null(첫 실행이라 NEW 표시를 하지 않는다). */
    fun seenLinks(agency: AgencyId): List<String>? =
        preferences.getString("$SEEN_PREFIX${agency.name}", null)?.split(KEYWORD_SEPARATOR)?.filter { it.isNotBlank() }

    fun saveSeenLinks(agency: AgencyId, links: List<String>) {
        preferences.edit()
            .putString("$SEEN_PREFIX${agency.name}", links.distinct().takeLast(300).joinToString(KEYWORD_SEPARATOR))
            .apply()
    }

    /** 사용자가 저장함에 담은 보도자료·보고서. */
    fun savedReleases(): List<ReleaseItem> =
        preferences.getString(SAVED_RELEASES, null)?.split(RECORD_SEPARATOR)?.mapNotNull { record ->
            val parts = record.split(KEYWORD_SEPARATOR)
            if (parts.size != 4) return@mapNotNull null
            val agency = AgencyId.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            ReleaseItem(agency, parts[1], parts[2], parts[3].takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        }.orEmpty()

    fun saveReleases(items: List<ReleaseItem>) {
        preferences.edit()
            .putString(SAVED_RELEASES, items.joinToString(RECORD_SEPARATOR) {
                listOf(it.agency.name, it.title, it.link, it.date?.toString().orEmpty()).joinToString(KEYWORD_SEPARATOR)
            })
            .apply()
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
        private const val LINKS_MIGRATED_V051 = "links_migrated_v051"
        const val LINKS = "useful_links"
        private const val SAVED_RELEASES = "saved_releases"
        private const val SEEN_PREFIX = "seen_"
        private const val KEYWORD_SEPARATOR = "\u001F"
        private const val RECORD_SEPARATOR = "\u001E"
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
