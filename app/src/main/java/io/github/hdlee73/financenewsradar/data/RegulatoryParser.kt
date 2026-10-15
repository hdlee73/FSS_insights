package io.github.hdlee73.financenewsradar.data

import java.net.URI
import java.time.LocalDate

/**
 * 유권해석·비조치·행정지도 게시판(표 형태 목록)에서 (제목, 링크, 날짜, 시행 상태)를 뽑는 의존성 없는 파서.
 * 사이트별 마크업에 묶이지 않도록 머리글(제목/시행일/시행여부) 이름으로 열을 찾고, 못 찾으면 행 안의 글에서 추정한다.
 */
object RegulatoryParser {
    data class Row(
        val title: String, val link: String?, val date: LocalDate?, val statusText: String?,
        val serial: String? = null, val dept: String? = null
    )

    /** 목록 종류: 제재공시(기관·요구일·부서), 법령해석·비조치(제목·일련번호), 행정지도(제목·부서·시행일·시행여부). */
    enum class ListKind { GENERIC, SANCTION, OPINION, GUIDANCE }

    private val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val noise = Regex("<!--.*?-->|<(script|style|noscript)\\b.*?</\\1>", opts)
    private val rowRe = Regex("<tr\\b([^>]*)>(.*?)</tr>", opts)
    private val cellRe = Regex("<(td|th)\\b[^>]*>(.*?)</\\1>", opts)
    private val anchorRe = Regex("<a\\b([^>]*)>(.*?)</a>", opts)
    private val hrefRe = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val tagRe = Regex("<[^>]+>")
    private val blockRe = Regex("</?(p|div|li|ul|ol|tr|td|th|dl|dt|dd|h[1-6]|br|section|article|table|thead|tbody)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val dateRe = Regex("(20\\d{2})\\s*[-./년]\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})")
    private val titleHeader = Regex("제목|건명|명칭|지도명|사안|안건")
    private val implHeader = Regex("시행\\s*일")
    private val statusHeader = Regex("시행\\s*여부|시행\\s*상태|상태|현황|진행")
    private val inForceRe = Regex("시행\\s*중|운영\\s*중|적용\\s*중|유효")
    private val endedRe = Regex("폐지|종료|해제|효력\\s*상실|실효|종결|종료됨|만료")
    private val footerRe = Regex("개인정보\\s*처리방침|저작권\\s*정책|Copyright|COPYRIGHT|찾아오시는\\s*길")

    private val shortDateRe = Regex("(?<!\\d)(\\d{2})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})(?!\\d)")
    private val compactDateRe = Regex("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)")

    private fun ymd(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** 2026-09-30, 2026.9.30, 2026년 9월 30일, 26.09.30, 20260930 모두 읽는다. */
    fun parseDate(text: String): LocalDate? {
        dateRe.find(text)?.let { m -> ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        compactDateRe.find(text)?.let { m -> ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        shortDateRe.find(text)?.let { m -> ymd(2000 + m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        return null
    }

    /** 시행 상태 글자에서 시행중 여부를 읽는다. 종료 표현이 우선하며, 알 수 없으면 null. */
    fun statusOf(text: String?): Boolean? {
        if (text.isNullOrBlank()) return null
        if (endedRe.containsMatchIn(text)) return false
        if (inForceRe.containsMatchIn(text)) return true
        return null
    }

    private fun cellText(html: String) = HtmlListParser.compact(HtmlListParser.decodeEntities(tagRe.replace(html, " ")))

    private fun resolve(base: URI?, link: String): String? {
        val raw = HtmlListParser.decodeEntities(link).trim()
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript", true)) return null
        return runCatching {
            val target = URI(raw.replace(" ", "%20"))
            val absolute = if (target.isAbsolute) target else base?.resolve(target) ?: return null
            absolute.toString().takeIf { absolute.scheme == "http" || absolute.scheme == "https" }
        }.getOrNull()
    }

    /** 열 이름 규칙. 머리글 이름으로 열을 찾는다. */
    private val orgHeader = Regex("제재\\s*대상\\s*기관|대상\\s*기관|기관명|금융회사|회사명")
    private val sanctionDateHeader = Regex("제재\\s*조치\\s*요구일|조치\\s*요구일|요구일")
    private val deptHeader = Regex("관련\\s*부서|소관\\s*부서|담당\\s*부서|부서")
    private val serialHeader = Regex("일련\\s*번호|연번|번호|No\\.?")
    private val detailHrefHint = Regex("view|detail|read|seq|idx|no=|id=|\\.do\\?", RegexOption.IGNORE_CASE)
    private val scriptPathRe = Regex("['\"](/[^'\"\\s]*\\.do[^'\"\\s]*)['\"]")

    /** href/onclick 안의 `함수(인자, …)` 호출. 상세 주소를 스크립트로 만드는 게시판용. */
    data class JsCall(val fn: String, val args: List<String>)

    private val jsCallRe = Regex("(?:href|onclick)\\s*=\\s*[\"'](?:\\s*javascript:)?\\s*(?:return\\s+)?([A-Za-z_$][\\w$.]*)\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
    private val dataIdRe = Regex("data-(?:idx|seq|id|no|key)[\\w-]*\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private fun jsCallOf(vararg fragments: String): JsCall? {
        for (f in fragments) {
            for (m in jsCallRe.findAll(f)) {
                val fn = m.groupValues[1]
                if (fn.equals("void", true) || fn.equals("alert", true) || fn.contains("download", true) || fn.contains("print", true)) continue
                val args = m.groupValues[2].split(',').map { it.trim().trim('\'', '"') }.filter { it.isNotBlank() }
                if (args.isNotEmpty()) return JsCall(fn, args)
            }
            dataIdRe.find(f)?.let { return JsCall("", listOf(it.groupValues[1])) }
        }
        return null
    }

    /**
     * 페이지의 스크립트에서 [call.fn] 정의를 찾아 상세 주소(.do 경로와 첫 인자 이름)를 알아내고,
     * 못 찾으면 출처별 기본 경로([defaultPath], [defaultParam])를 쓴다. 목록 주소의 stNo/muNo/muGpNo는 그대로 붙인다.
     */
    internal fun detailLink(rawHtml: String, base: URI?, listUrl: String, call: JsCall, defaultPath: String?, defaultParam: String?): String? {
        var path: String? = null
        var param: String? = null
        if (call.fn.isNotEmpty()) {
            val name = Regex.escape(call.fn.substringAfterLast('.'))
            val def = Regex("function\\s+$name\\s*\\(|$name\\s*[:=]\\s*function\\s*\\(").find(rawHtml)
            if (def != null) {
                val body = rawHtml.substring(def.range.last, (def.range.last + 900).coerceAtMost(rawHtml.length))
                Regex("[\"']([^\"'\\s]*\\.do)(\\?[^\"']*)?[\"']").find(body)?.let { m ->
                    path = m.groupValues[1]
                    param = Regex("([A-Za-z_]\\w*)\\s*=").find(m.groupValues[2])?.groupValues?.get(1)
                }
            }
        }
        path = path ?: defaultPath
        param = param ?: defaultParam
        val finalPath = path ?: return null
        val finalParam = param ?: return null
        val extras = runCatching { URI(listUrl).rawQuery.orEmpty() }.getOrDefault("")
            .split('&').filter { it.startsWith("stNo=") || it.startsWith("muNo=") || it.startsWith("muGpNo=") }
        val query = (listOf(finalParam + "=" + java.net.URLEncoder.encode(call.args.first(), "UTF-8")) + extras).joinToString("&")
        return resolve(base, "$finalPath?$query")
    }

    private fun anchorsOf(row: String, base: URI?): List<Triple<String, String?, String>> =
        anchorRe.findAll(row).map { a ->
            val attrs = a.groupValues[1]
            val href = hrefRe.find(attrs)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            // href가 javascript 호출이면 속성 안의 .do 경로 문자열을 대신 찾는다.
            val link = href?.let { resolve(base, it) } ?: scriptPathRe.find(attrs)?.let { resolve(base, it.groupValues[1]) }
            Triple(cellText(a.groupValues[2]), link, attrs)
        }.filter { it.first.length >= 2 }.toList()

    /**
     * 게시판 목록을 읽는다. 표(`tr/td`)를 우선하고 머리글 이름으로 열을 찾으며,
     * 표가 없으면 날짜와 상세 링크를 가진 `li` 항목을 읽는다. 사이트 메뉴 같은 링크 모음은 읽지 않는다(없으면 빈 목록).
     */
    fun parseList(html: String, baseUrl: String, kind: ListKind = ListKind.GENERIC, detailPath: String? = null, detailParam: String? = null): List<Row> {
        val clean = noise.replace(html, " ")
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val out = mutableListOf<Row>()
        var headers: List<String> = emptyList()
        for (rowMatch in rowRe.findAll(clean)) {
            val trAttrs = rowMatch.groupValues[1]
            val row = rowMatch.groupValues[2]
            val cells = cellRe.findAll(row).toList()
            if (cells.isEmpty()) continue
            val texts = cells.map { cellText(it.groupValues[2]) }
            if (cells.none { it.groupValues[1].equals("td", true) }) {
                if (headers.isEmpty() || texts.size > headers.size) headers = texts
                continue
            }
            val aligned = headers.size == texts.size
            fun idx(re: Regex) = if (aligned) headers.indexOfFirst { re.containsMatchIn(it) } else -1
            fun cell(re: Regex) = idx(re).takeIf { it >= 0 }?.let { texts.getOrNull(it) }?.takeIf { it.isNotBlank() }
            val anchors = anchorsOf(row, base)
            val dateIdx = if (kind == ListKind.SANCTION) idx(sanctionDateHeader).takeIf { it >= 0 } ?: idx(implHeader) else idx(implHeader)
            val date = texts.getOrNull(dateIdx)?.let { parseDate(it) } ?: texts.firstNotNullOfOrNull { parseDate(it) }
                ?: parseDate(HtmlListParser.decodeEntities(row))
            val dept = cell(deptHeader)
            val statusIdx = idx(statusHeader)
            val statusText = texts.getOrNull(statusIdx)?.takeIf { it.isNotBlank() }
                ?: if (kind == ListKind.GUIDANCE) texts.firstOrNull { statusOf(it) != null && it.length <= 12 } else null
            val serial = cell(serialHeader) ?: texts.firstOrNull()?.takeIf { kind == ListKind.OPINION && it.length <= 12 && it.any(Char::isDigit) }

            val title: String? = when (kind) {
                ListKind.SANCTION -> cell(orgHeader)
                else -> cell(titleHeader)
            } ?: anchors.maxByOrNull { it.first.length }?.first?.takeIf { it.length >= 2 }
                ?: texts.filter { it.length >= 4 && parseDate(it) == null && it != serial }.maxByOrNull { it.length }
            if (title == null) continue
            val titled = anchors.firstOrNull { title.startsWith(it.first.take(10)) || it.first.startsWith(title.take(10)) }
            val link = titled?.second ?: anchors.firstNotNullOfOrNull { it.second }
                ?: jsCallOf(titled?.third.orEmpty(), trAttrs, row)?.let { detailLink(html, base, baseUrl, it, detailPath, detailParam) }
            out += Row(title, link, date, statusText, serial, dept)
        }
        if (out.isNotEmpty()) return out
        return parseListItems(clean, base)
    }

    private val liRe = Regex("<li\\b[^>]*>(.*?)</li>", opts)

    /** 표가 아닌 `li` 목록: 날짜와 상세 링크가 함께 있는 항목만 게시글로 본다. */
    private fun parseListItems(clean: String, base: URI?): List<Row> =
        liRe.findAll(clean).mapNotNull { m ->
            val body = m.groupValues[1]
            val text = cellText(body)
            val date = parseDate(text) ?: return@mapNotNull null
            val anchor = anchorsOf(body, base).filter { it.second != null && detailHrefHint.containsMatchIn(it.second!!) }
                .maxByOrNull { it.first.length } ?: return@mapNotNull null
            if (anchor.first.length < 4) return@mapNotNull null
            Row(anchor.first, anchor.second, date, null, null, null)
        }.toList()

    /** 상세 페이지 HTML을 읽을 수 있는 글로 바꾼다. [title]이 나오는 지점부터 쓰고 하단 안내문은 자른다. */
    fun htmlToText(html: String, title: String = "", maxLength: Int = 12_000): String {
        val body = noise.replace(html, " ")
        val text = HtmlListParser.decodeEntities(tagRe.replace(blockRe.replace(body, "\n"), " "))
        val lines = text.lines().map { HtmlListParser.compact(it) }.filter { it.isNotEmpty() }
        var joined = lines.joinToString("\n")
        if (title.length >= 6) {
            val at = joined.indexOf(title.take(15))
            if (at > 0) joined = joined.substring(at)
        }
        footerRe.find(joined)?.let { if (it.range.first > 200) joined = joined.substring(0, it.range.first) }
        return joined.take(maxLength)
    }

    private val implDetail = Regex("(?:시행\\s*일|제재\\s*조치\\s*요구일)(?:자)?\\s*[:：]?\\s*(20\\d{2}\\s*[-./년]\\s*\\d{1,2}\\s*[-./월]\\s*\\d{1,2})")
    private val statusDetail = Regex("(?:시행\\s*여부|시행\\s*상태|상\\s*태|현\\s*황)\\s*[:：]?\\s*([가-힣]{2,8})")

    /** 상세 글에서 시행일과 시행 상태를 보충해서 읽는다. */
    fun detailFields(text: String): Pair<LocalDate?, String?> =
        implDetail.find(text)?.let { parseDate(it.groupValues[1]) } to statusDetail.find(text)?.groupValues?.get(1)

    /** 공백으로 나눈 모든 낱말이 제목 또는 내용에 들어 있으면 일치. */
    fun matches(item: io.github.hdlee73.financenewsradar.model.RegItem, query: String): Boolean {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val haystack = item.title + "\n" + item.body
        return tokens.all { haystack.contains(it, ignoreCase = true) }
    }

    /** 내용에서 검색어가 처음 나오는 곳 주변의 짧은 발췌. 제목에만 있으면 null. */
    fun snippet(item: io.github.hdlee73.financenewsradar.model.RegItem, query: String, radius: Int = 45): String? {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (token in tokens) {
            if (item.title.contains(token, ignoreCase = true)) continue
            val at = item.body.indexOf(token, ignoreCase = true)
            if (at < 0) continue
            val from = (at - radius).coerceAtLeast(0)
            val to = (at + token.length + radius).coerceAtMost(item.body.length)
            return (if (from > 0) "…" else "") + item.body.substring(from, to).replace('\n', ' ') + (if (to < item.body.length) "…" else "")
        }
        return null
    }
}
