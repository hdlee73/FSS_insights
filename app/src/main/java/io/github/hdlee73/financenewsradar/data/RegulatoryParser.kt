package io.github.hdlee73.financenewsradar.data

import java.net.URI
import java.time.LocalDate

/**
 * 유권해석·비조치·행정지도 게시판(표 형태 목록)에서 (제목, 링크, 날짜, 시행 상태)를 뽑는 의존성 없는 파서.
 * 사이트별 마크업에 묶이지 않도록 머리글(제목/시행일/시행여부) 이름으로 열을 찾고, 못 찾으면 행 안의 글에서 추정한다.
 */
object RegulatoryParser {
    data class Row(val title: String, val link: String?, val date: LocalDate?, val statusText: String?)

    private val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val noise = Regex("<!--.*?-->|<(script|style|noscript)\\b.*?</\\1>", opts)
    private val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", opts)
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

    fun parseDate(text: String): LocalDate? {
        val m = dateRe.find(text) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
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

    fun parseList(html: String, baseUrl: String): List<Row> {
        val clean = noise.replace(html, " ")
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val rows = rowRe.findAll(clean).map { it.groupValues[1] }.toList()
        var headers: List<String> = emptyList()
        val out = mutableListOf<Row>()
        for (row in rows) {
            val cells = cellRe.findAll(row).toList()
            if (cells.isEmpty()) continue
            val texts = cells.map { cellText(it.groupValues[2]) }
            if (cells.none { it.groupValues[1].equals("td", true) }) {
                if (headers.isEmpty()) headers = texts
                continue
            }
            val aligned = headers.size == texts.size
            fun idx(re: Regex) = if (aligned) headers.indexOfFirst { re.containsMatchIn(it) } else -1
            val titleIdx = idx(titleHeader)
            val implIdx = idx(implHeader)
            val statusIdx = idx(statusHeader)

            val anchors = anchorRe.findAll(row).map { a ->
                val href = hrefRe.find(a.groupValues[1])?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                cellText(a.groupValues[2]) to href
            }.filter { it.first.length >= 2 }.toList()

            val title = texts.getOrNull(titleIdx)?.takeIf { it.length >= 4 }
                ?: anchors.maxByOrNull { it.first.length }?.first?.takeIf { it.length >= 4 }
                ?: texts.filter { it.length >= 6 && parseDate(it) == null }.maxByOrNull { it.length }
                ?: continue
            // 제목이 들어 있는 링크를 우선하고, 없으면 행의 첫 번째 쓸 수 있는 링크.
            val link = anchors.firstOrNull { title.startsWith(it.first.take(10)) || it.first.startsWith(title.take(10)) }
                ?.second?.let { resolve(base, it) }
                ?: anchors.firstNotNullOfOrNull { a -> a.second?.let { resolve(base, it) } }

            val dateText = if (implIdx >= 0) texts.getOrNull(implIdx).orEmpty() else ""
            val date = parseDate(dateText) ?: texts.firstNotNullOfOrNull { parseDate(it) }
            val statusText = texts.getOrNull(statusIdx)?.takeIf { it.isNotBlank() }
                ?: texts.firstOrNull { statusOf(it) != null && it.length <= 12 }
            out += Row(title, link, date, statusText)
        }
        if (out.isNotEmpty()) return out
        // 표가 아닌 목록이면 범용 추출로 한 번 더 시도한다.
        return HtmlListParser.extractGeneric(html, baseUrl).map { Row(it.title, it.url, it.date, null) }
    }

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

    private val implDetail = Regex("시행\\s*일(?:자)?\\s*[:：]?\\s*(20\\d{2}\\s*[-./년]\\s*\\d{1,2}\\s*[-./월]\\s*\\d{1,2})")
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
