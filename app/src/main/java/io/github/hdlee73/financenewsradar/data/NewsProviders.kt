package io.github.hdlee73.financenewsradar.data

import android.text.Html
import io.github.hdlee73.financenewsradar.model.NaverCredentials
import io.github.hdlee73.financenewsradar.model.NewsArticle
import io.github.hdlee73.financenewsradar.model.SearchPage
import io.github.hdlee73.financenewsradar.model.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

interface NewsProvider {
    suspend fun search(query: String, timeRange: TimeRange, start: Int = 1, pageSize: Int = 100): SearchPage
}

class GoogleNewsRssProvider : NewsProvider {
    override suspend fun search(query: String, timeRange: TimeRange, start: Int, pageSize: Int): SearchPage =
        withContext(Dispatchers.IO) {
            if (start > 1) return@withContext SearchPage(emptyList())
            val fullQuery = "$query ${timeRange.googleToken}"
            val encoded = URLEncoder.encode(fullQuery, StandardCharsets.UTF_8.toString())
            val url = "https://news.google.com/rss/search?q=$encoded&hl=ko&gl=KR&ceid=KR:ko"
            val xml = Http.get(url)
            SearchPage(GoogleRssParser.parse(xml).take(pageSize), hasMore = false)
        }
}

class NaverNewsProvider(private val credentials: NaverCredentials) : NewsProvider {
    override suspend fun search(query: String, timeRange: TimeRange, start: Int, pageSize: Int): SearchPage =
        withContext(Dispatchers.IO) {
            require(credentials.isComplete) { "네이버 Client ID와 Client Secret을 설정해 주세요." }
            val size = pageSize.coerceIn(1, 100)
            val safeStart = start.coerceIn(1, 1000)
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://openapi.naver.com/v1/search/news.json?query=$encoded&display=$size&start=$safeStart&sort=date"
            val body = Http.get(
                url,
                mapOf(
                    "X-Naver-Client-Id" to credentials.clientId,
                    "X-Naver-Client-Secret" to credentials.clientSecret
                )
            )
            val root = JSONObject(body)
            val total = root.optInt("total", 0).coerceAtMost(1000)
            val items = root.optJSONArray("items")
            val parsed = buildList {
                if (items != null) for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val original = item.optString("originallink")
                    val fallback = item.optString("link")
                    val link = original.ifBlank { fallback }
                    val title = cleanHtml(item.optString("title"))
                    val summary = cleanHtml(item.optString("description"))
                    val source = PublisherCatalog.canonicalName("", link)
                    add(
                        NewsArticle(
                            title = title,
                            link = link,
                            source = source,
                            publishedAt = parseRfcDate(item.optString("pubDate")),
                            summary = summary.ifBlank { "기사 주요 내용은 원문에서 확인하세요." }
                        )
                    )
                }
            }
            val cutoff = Instant.now().minus(timeRange.duration)
            val filtered = parsed.filter { it.publishedAt >= cutoff }
            val next = safeStart + size
            val reachedCutoff = parsed.lastOrNull()?.publishedAt?.let { it < cutoff } ?: false
            SearchPage(filtered, hasMore = next <= total && next <= 1000 && !reachedCutoff, nextStart = next)
        }
}

class CombinedNewsProvider(private val credentials: NaverCredentials) : NewsProvider {
    override suspend fun search(query: String, timeRange: TimeRange, start: Int, pageSize: Int): SearchPage =
        supervisorScope {
            val providers = buildList<NewsProvider> {
                add(GoogleNewsRssProvider())
                if (credentials.isComplete) add(NaverNewsProvider(credentials))
            }
            val results = providers.map { provider ->
                async { runCatching { provider.search(query, timeRange, start, pageSize) } }
            }.awaitAll()
            val pages = results.mapNotNull { it.getOrNull() }
            if (pages.isEmpty()) throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IllegalStateException("기사를 불러오지 못했습니다.")
            val articles = pages.flatMap { it.articles }
            SearchPage(
                articles = articles,
                hasMore = pages.any { it.hasMore },
                nextStart = pages.filter { it.hasMore }.maxOfOrNull { it.nextStart } ?: 1,
                fetchedCount = pages.sumOf { it.fetchedCount },
                failedQueryCount = results.count { it.isFailure } + pages.sumOf { it.failedQueryCount }
            )
        }
}

object GoogleRssParser {
    fun parse(xml: String): List<NewsArticle> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
        val result = mutableListOf<NewsArticle>()
        var event = parser.eventType
        var inItem = false
        var tag = ""
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var source = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "item") {
                        inItem = true
                        title = ""; link = ""; description = ""; pubDate = ""; source = ""
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val value = parser.text.orEmpty()
                    when (tag) {
                        "title" -> title += value
                        "link" -> link += value
                        "description" -> description += value
                        "pubDate" -> pubDate += value
                        "source" -> source += value
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") {
                        val cleanTitle = cleanHtml(title)
                        val inferredSource = source.ifBlank {
                            cleanTitle.substringAfterLast(" - ", "")
                        }
                        val displayTitle = if (inferredSource.isNotBlank()) {
                            cleanTitle.removeSuffix(" - $inferredSource")
                        } else cleanTitle
                        val canonicalSource = PublisherCatalog.canonicalName(inferredSource, link.trim())
                        val cleanDescription = cleanHtml(description)
                            .removePrefix(displayTitle)
                            .removePrefix(canonicalSource)
                            .trim(' ', '-', '–', '—', '|')
                        if (displayTitle.isNotBlank() && link.isNotBlank()) {
                            result += NewsArticle(
                                title = displayTitle,
                                link = link.trim(),
                                source = canonicalSource,
                                publishedAt = parseRfcDate(pubDate),
                                summary = cleanDescription.ifBlank { "기사 주요 내용은 원문에서 확인하세요." }
                            )
                        }
                        inItem = false
                    }
                    tag = ""
                }
            }
            event = parser.next()
        }
        return result
    }
}

private object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml, text/xml")
            connection.setRequestProperty("User-Agent", "FinanceNewsRadar-Android/0.1")
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("errorMessage") }.getOrDefault("")
                error(detail.ifBlank { "뉴스 서버 응답 오류 ($code)" })
            }
            text
        } finally {
            connection.disconnect()
        }
    }
}

@Suppress("DEPRECATION")
private fun cleanHtml(raw: String): String = NewsText.compact(
    Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
)

private fun parseRfcDate(value: String): Instant {
    if (value.isBlank()) return Instant.EPOCH
    return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        .recoverCatching {
            ZonedDateTime.parse(
                value,
                DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            ).toInstant()
        }
        .getOrDefault(Instant.EPOCH)
}
