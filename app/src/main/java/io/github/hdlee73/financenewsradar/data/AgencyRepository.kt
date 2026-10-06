package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.CustomInstitute
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import io.github.hdlee73.financenewsradar.model.ReleasePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** 기관별 목록 주소·링크 패턴. 사이트가 개편되면 이 파일만 고치면 된다. */
internal object AgencySources {
    class Source(
        val listUrl: (page: Int, pageSize: Int) -> String,
        /** 사이트 자체 검색 주소(없으면 목록을 받아 앱에서 제목을 걸러낸다). */
        val searchUrl: ((query: String, page: Int) -> String)? = null,
        val linkPattern: Regex = Regex(""),
        val isRss: Boolean = false,
        /** 페이지 이동 방식을 확인하지 못했거나 RSS처럼 한 번에 받는 경우 false. */
        val pageable: Boolean = true,
        val pageSize: Int = 10,
        /** 목록과 무관한 메뉴 링크를 거르기 위해 날짜가 있는 항목만 쓴다. */
        val requireDate: Boolean = false
    )

    private fun enc(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())

    fun of(agency: AgencyId): Source = when (agency) {
        AgencyId.FSS -> Source(
            listUrl = { page, _ -> "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218&pageIndex=$page" },
            searchUrl = { q, page -> "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218&pageIndex=$page&searchCnd=1&searchWrd=${enc(q)}" },
            linkPattern = Regex("""view\.do\?.*nttId=\d+""")
        )
        AgencyId.FSC -> Source(
            listUrl = { page, _ -> "https://www.fsc.go.kr/no010101?curPage=$page" },
            searchUrl = { q, page -> "https://www.fsc.go.kr/no010101?curPage=$page&srchKey=sj&srchText=${enc(q)}" },
            linkPattern = Regex("""no010101/\d+""")
        )
        AgencyId.SEC -> Source(
            // 검색·페이지 이동이 없는 RSS(최근 보도자료). 과거 자료 검색은 앱이 읽어 온 범위에서 제목으로 거른다.
            listUrl = { _, _ -> "https://www.sec.gov/news/pressreleases.rss" },
            isRss = true,
            pageable = false
        )
        AgencyId.KCMI -> Source(
            listUrl = { page, size -> "https://www.kcmi.re.kr/report/report_list?pg=$page&pp=$size" },
            // 한 행에 바로보기가 둘(보도자료 fty=004010 / 보고서 fty=004003)이라 보고서 쪽만 쓴다.
            linkPattern = Regex("""flexer/view\?[^"\s]*fty=004003"""),
            pageSize = 30
        )
        AgencyId.KIF -> Source(
            listUrl = { _, _ -> "https://www.kif.re.kr/kif4/publication/pub_list?mid=10" },
            // 목록의 상세 링크는 상대경로 `pub_detail?mid=10&nid=…`. 날짜는 "2026-09"(년-월)만 있다.
            linkPattern = Regex("""pub_detail\?[^"\s]*mid=10(&|$)"""),
            pageable = false,
            requireDate = true
        )
        AgencyId.CUSTOM -> error("사용자가 추가한 연구소는 별도 경로로 읽습니다.")
        AgencyId.IOSCO -> Source(
            listUrl = { page, _ -> "https://www.iosco.org/publications/?subsection=public_reports" + if (page > 1) "&page=$page" else "" },
            searchUrl = { q, page ->
                "https://www.iosco.org/publications/?subsection=public_reports&keywords=${enc(q)}&keywordsTitle=on" +
                    if (page > 1) "&page=$page" else ""
            },
            linkPattern = Regex("""(?i)pubdocs/pdf/IOSCOPD\d+\.pdf""")
        )
    }

    /** 앱 안 보기 화면에서 열 "사이트에서 직접 검색" 주소. */
    fun siteSearchUrl(agency: AgencyId, query: String): String {
        val q = enc(query)
        return when (agency) {
            AgencyId.FSS -> "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218&searchCnd=1&searchWrd=$q"
            AgencyId.FSC -> "https://www.fsc.go.kr/no010101?srchKey=sj&srchText=$q"
            AgencyId.SEC -> "https://www.google.com/search?q=site%3Asec.gov%2Fnewsroom%2Fpress-releases+$q"
            AgencyId.KCMI -> "https://www.google.com/search?q=site%3Akcmi.re.kr+$q"
            AgencyId.KIF -> "https://www.google.com/search?q=site%3Akif.re.kr+$q"
            AgencyId.IOSCO -> "https://www.iosco.org/publications/?subsection=public_reports&keywords=$q"
            AgencyId.CUSTOM -> "https://www.google.com/search?q=$q"
        }
    }

    /** 검색어를 공백으로 나눠 모든 단어가 제목에 들어 있으면 일치. */
    fun matches(title: String, query: String): Boolean {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { title.contains(it, ignoreCase = true) }
    }
}

class AgencyRepository(private val context: android.content.Context) {
    /** 가장 최근 [count]건. */
    suspend fun latest(agency: AgencyId, count: Int = 10): List<ReleaseItem> {
        val source = AgencySources.of(agency)
        val items = fetchPage(agency, source, source.listUrl(1, source.pageSize)).toMutableList()
        // 한 페이지에 count건이 안 되면 다음 페이지를 이어 읽는다(실패해도 이미 읽은 것은 보여 준다).
        var page = 2
        val target = if (agency == AgencyId.KCMI || agency == AgencyId.IOSCO) count * 2 else count
        while (items.size < target && source.pageable && page <= 4) {
            val more = runCatching { fetchPage(agency, source, source.listUrl(page, source.pageSize)) }.getOrNull() ?: break
            items += more.filter { next -> items.none { it.link == next.link } }
            page++
        }
        // 날짜가 있는 항목을 최신순으로(날짜 없는 항목은 뒤로). 사이트 목록 순서가 들쭉날쭉해도 '최근 N건'이 되도록.
        return items.sortedByDescending { it.date }.take(count)
    }

    /**
     * 과거 자료 검색. [page]부터 읽기 시작해 일치 항목이 모이거나 한도에 닿으면 멈춘다.
     * 결과는 항상 앱에서 제목 기준으로 한 번 더 걸러 서버가 검색어를 무시해도 엉뚱한 목록이 나오지 않는다.
     */
    suspend fun search(agency: AgencyId, query: String, page: Int = 1): ReleasePage {
        val source = AgencySources.of(agency)
        val found = LinkedHashMap<String, ReleaseItem>()
        var current = page
        var hasMore = false
        var scanned = 0
        while (scanned < MAX_PAGES_PER_CALL) {
            val url = source.searchUrl?.invoke(query, current) ?: source.listUrl(current, source.pageSize)
            val items = if (current == page) fetchPage(agency, source, url)
            else runCatching { fetchPage(agency, source, url) }.getOrDefault(emptyList())
            items.filter { AgencySources.matches(it.title, query) }.forEach { found.putIfAbsent(it.link, it) }
            scanned++
            val canContinue = source.pageable && items.isNotEmpty() && current < MAX_PAGE
            current++
            hasMore = canContinue
            if (!canContinue || found.size >= TARGET_RESULTS) break
        }
        return ReleasePage(found.values.toList(), hasMore = hasMore, nextPage = current)
    }

    private suspend fun fetchPage(agency: AgencyId, source: AgencySources.Source, url: String): List<ReleaseItem> =
        fetchParsed(agency.label, url) { body -> parse(source, body, url) }
            .map { ReleaseItem(agency, it.title, it.url, it.date) }

    /** 사용자가 추가한 연구소의 목록(최근 [count]건). 주소 패턴을 모르므로 범용 추출을 쓴다. */
    suspend fun latestCustom(institute: CustomInstitute, count: Int = 10): List<ReleaseItem> =
        fetchParsed(institute.name, institute.url) { body -> HtmlListParser.extractGeneric(body, institute.url) }
            .take(count)
            .map { ReleaseItem(AgencyId.CUSTOM, it.title, it.url, it.date, institute.name) }

    /** 추가한 연구소는 검색 주소를 모르므로 읽어 온 목록(최대 30건)에서 제목을 걸러 낸다. */
    suspend fun searchCustom(institute: CustomInstitute, query: String): List<ReleaseItem> =
        latestCustom(institute, 30).filter { AgencySources.matches(it.title, query) }

    private suspend fun fetchParsed(label: String, url: String, parse: (String) -> List<ParsedLink>): List<ParsedLink> {
        var parsed: List<ParsedLink> = emptyList()
        var lastBody = ""
        var failure: Throwable? = null
        // 1차: 가벼운 HTTP 요청. 2차: 막히거나 목록이 비면 숨은 WebView로 실제 화면의 HTML을 읽는다.
        for (useWebView in listOf(false, true)) {
            val body = try {
                if (useWebView) WebViewFetcher.get(context, url) { html -> parse(html).isNotEmpty() } else HtmlFetcher.get(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = failure ?: e
                continue
            }
            lastBody = body
            parsed = parse(body)
            if (parsed.isNotEmpty()) break
        }
        if (parsed.isEmpty()) {
            val detail = if (lastBody.isEmpty()) (failure?.message ?: "응답 없음")
            else "응답 ${lastBody.length}자 · 링크 ${HtmlListParser.anchorCount(lastBody)}개 · ${HtmlListParser.sampleLinks(lastBody)}"
            error("$label 목록을 읽지 못했습니다. 사이트가 접속을 막았거나 구조가 바뀌었을 수 있습니다. ‘사이트에서 보기’를 이용해 주세요. ($detail)")
        }
        return parsed
    }

    private fun parse(source: AgencySources.Source, body: String, url: String): List<ParsedLink> {
        var parsed = if (source.isRss) HtmlListParser.parseRss(body, url)
        else HtmlListParser.extract(body, url, source.linkPattern)
        if (source.requireDate) parsed = parsed.filter { it.date != null }.sortedByDescending { it.date }
        // 링크 형식이 예상과 다르면(금융연구원 등) 날짜가 붙은 링크를 목록으로 간주하는 방식으로 한 번 더 시도한다.
        if (parsed.isEmpty() && source.requireDate) parsed = HtmlListParser.extractLoose(body, url)
        return parsed
    }

    private companion object {
        const val MAX_PAGES_PER_CALL = 4
        const val MAX_PAGE = 60
        const val TARGET_RESULTS = 10
    }
}

internal object HtmlFetcher {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.5")
            val code = connection.responseCode
            if (code !in 200..299) error("서버 응답 오류 ($code)")
            val bytes = connection.inputStream.use { it.readBytes() }
            String(bytes, detectCharset(connection.contentType, bytes))
        } finally {
            connection.disconnect()
        }
    }

    private fun detectCharset(contentType: String?, bytes: ByteArray): Charset {
        val fromHeader = contentType?.let { Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val head = String(bytes, 0, minOf(bytes.size, 2048), StandardCharsets.ISO_8859_1)
        val fromMeta = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return (fromHeader ?: fromMeta)?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
    }
}
