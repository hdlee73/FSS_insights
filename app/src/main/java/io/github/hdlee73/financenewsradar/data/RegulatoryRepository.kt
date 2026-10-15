package io.github.hdlee73.financenewsradar.data

import android.content.Context
import io.github.hdlee73.financenewsradar.model.RegItem
import io.github.hdlee73.financenewsradar.model.RegSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * 유권해석·비조치의견서·행정지도 목록과 상세 내용을 읽어 온다.
 * 목록(최대 20건)을 읽은 뒤 각 항목의 상세 페이지를 받아 내용 전체를 검색할 수 있게 한다.
 * 상세를 읽지 못해도 목록(제목)은 남는다. 읽은 결과는 기기에 보관해 다음에 바로 보여 준다.
 */
class RegulatoryRepository(private val context: Context) {
    /** 마지막으로 보관해 둔 목록(없으면 빈 목록). */
    suspend fun cached(source: RegSource): List<RegItem> = withContext(Dispatchers.IO) {
        runCatching { readCache(source) }.getOrDefault(emptyList())
    }

    /**
     * 최신 [RegSource.count]건. 목록이 읽히면 곧바로 [onList]로 넘겨 먼저 보여 주고,
     * 상세 내용을 받는 대로 전체를 다시 [onList]로 넘긴다.
     */
    suspend fun load(source: RegSource, onList: (List<RegItem>) -> Unit = {}): List<RegItem> {
        val rows = fetchRows(source)
        var items = rows.map { RegItem(source, it.title, it.link ?: source.listUrl, it.date, "", it.statusText, RegulatoryParser.statusOf(it.statusText)) }
        onList(items)
        val bodies = Semaphore(DETAIL_PARALLEL)
        items = coroutineScope {
            items.map { item ->
                async {
                    bodies.withPermit { runCatching { withDetail(item) }.getOrElse { if (it is CancellationException) throw it else item } }
                }
            }.awaitAll()
        }
        // 행정지도는 시행일 기준 최신순, 나머지는 목록 순서(사이트의 최신순)를 따른다.
        if (source.isGuidance) items = items.sortedByDescending { it.date }
        runCatching { writeCache(source, items) }
        return items
    }

    private suspend fun fetchRows(source: RegSource): List<RegulatoryParser.Row> {
        val rows = LinkedHashMap<String, RegulatoryParser.Row>()
        var failure: Throwable? = null
        for (page in 1..MAX_LIST_PAGES) {
            val url = pageUrl(source.listUrl, page)
            val parsed = try {
                fetchHtml(url) { RegulatoryParser.parseList(it, url).isNotEmpty() }
                    .let { RegulatoryParser.parseList(it, url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = failure ?: e
                emptyList()
            }
            val before = rows.size
            parsed.forEach { rows.putIfAbsent(it.link?.takeIf { l -> l != source.listUrl } ?: it.title, it) }
            if (rows.size >= source.count || rows.size == before) break
        }
        if (rows.isEmpty()) {
            error("${source.label} 목록을 읽지 못했습니다. 사이트가 접속을 막았거나 구조가 바뀌었을 수 있습니다. ‘사이트에서 보기’를 이용해 주세요." +
                (failure?.message?.let { " ($it)" }.orEmpty()))
        }
        return rows.values.take(source.count)
    }

    private suspend fun withDetail(item: RegItem): RegItem {
        if (item.link == item.source.listUrl) return item
        val html = fetchHtml(item.link) { true }
        val text = RegulatoryParser.htmlToText(html, item.title)
        val (impl, status) = RegulatoryParser.detailFields(text)
        val statusText = item.statusText ?: status
        return item.copy(
            body = text,
            // 행정지도: 목록에 시행일·상태가 없으면 상세에서 보충한다.
            date = item.date ?: impl,
            statusText = statusText,
            inForce = item.inForce ?: RegulatoryParser.statusOf(statusText)
        )
    }

    /** 1차: 가벼운 HTTP 요청. 2차: 막히거나 비어 있으면 숨은 WebView로 실제 화면의 HTML을 읽는다. */
    private suspend fun fetchHtml(url: String, ready: (String) -> Boolean): String {
        var failure: Throwable? = null
        try {
            val body = HtmlFetcher.get(url)
            if (ready(body)) return body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        }
        return try {
            WebViewFetcher.get(context, url, isReady = ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw failure ?: e
        }
    }

    private fun cacheFile(source: RegSource) = File(context.cacheDir, "regulatory-${source.name.lowercase()}.json")

    private fun writeCache(source: RegSource, items: List<RegItem>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().apply {
                put("title", it.title); put("link", it.link); put("date", it.date?.toString())
                put("body", it.body); put("status", it.statusText); put("inForce", it.inForce)
            })
        }
        cacheFile(source).writeText(array.toString())
    }

    private fun readCache(source: RegSource): List<RegItem> {
        val file = cacheFile(source)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RegItem(
                source, o.getString("title"), o.getString("link"),
                o.optString("date").takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                o.optString("body"),
                o.optString("status").takeIf { it.isNotBlank() && it != "null" },
                if (o.has("inForce") && !o.isNull("inForce")) o.getBoolean("inForce") else null
            )
        }
    }

    companion object {
        private const val DETAIL_PARALLEL = 4
        private const val MAX_LIST_PAGES = 3

        /** 목록 주소에 쪽 번호와 쪽당 건수 인자를 붙인다(사이트가 쓰는 이름을 모르므로 흔한 이름을 함께 넣는다). */
        internal fun pageUrl(listUrl: String, page: Int): String =
            if (page <= 1) listUrl else "$listUrl&pageIndex=$page&curPage=$page"
    }
}
