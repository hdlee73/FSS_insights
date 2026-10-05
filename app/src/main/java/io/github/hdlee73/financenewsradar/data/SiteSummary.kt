package io.github.hdlee73.financenewsradar.data

/** 사이트 첫 화면 HTML에서 소개 문구(메타 설명)를 뽑는다. 직접 쓴 설명이 없는 사이트에만 쓴다. */
object SiteSummary {
    private val cache = HashMap<String, String>()

    fun clearCache() = cache.clear()

    suspend fun fetch(url: String): String {
        cache[url]?.let { return it }
        val text = runCatching { fromHtml(HtmlFetcher.get(url)) }.getOrDefault("")
        if (text.isNotBlank()) cache[url] = text
        return text
    }

    fun fromHtml(html: String): String {
        fun meta(key: String): String? {
            val tag = Regex("""<meta\s[^>]*(?:name|property)\s*=\s*["']$key["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
            return Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        }
        val raw = meta("description") ?: meta("og:description")
            ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
            ?: return ""
        return raw.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim().take(120)
    }
}
