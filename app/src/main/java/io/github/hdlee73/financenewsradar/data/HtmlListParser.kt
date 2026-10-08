package io.github.hdlee73.financenewsradar.data

import java.net.URI
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedLink(val title: String, val url: String, val date: LocalDate?, val author: String? = null)

/**
 * 게시판 목록 HTML에서 (제목, 링크, 날짜)를 뽑는 의존성 없는 파서.
 * 사이트별 마크업에 묶이지 않도록 "링크 주소 패턴"과 "링크 근처의 날짜"만 사용한다.
 * 링크 글자가 "View Report"처럼 제목이 아닌 경우에는 링크 앞쪽 글에서 제목을 찾는다.
 */
object HtmlListParser {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val noise = Regex("<!--.*?-->|<(script|style|noscript)\\b.*?</\\1>", options)
    private val anchor = Regex("<a\\b([^>]*)>(.*?)</a>", options)
    private val href = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")
    private val numericDate = Regex("(20\\d{2})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})")
    // "08 Sep 2026" 형식. 마감일 문구의 "1 December 2026"(월 전체 이름)은 일부러 제외한다.
    private val englishDate = Regex(
        "\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?![A-Za-z])\\.?,?\\s+(20\\d{2})",
        RegexOption.IGNORE_CASE
    )
    // "2026-09"처럼 일(日)이 없는 년-월 표기(한국금융연구원 등). 완전한 날짜가 없을 때만 쓴다.
    private val monthOnlyDate = Regex("(?<![\\d-])(20\\d{2})-(0[1-9]|1[0-2])(?![\\d-])")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val numericEntity = Regex("&#(x[0-9a-fA-F]+|\\d+);")
    private val rowOpen = Regex("<(tr|li)\\b", RegexOption.IGNORE_CASE)
    private val rowClose = Regex("</(tr|li)>", RegexOption.IGNORE_CASE)
    private val boldBlock = Regex("<(b|strong)\\b[^>]*>(.*?)</\\1>", options)
    private val blockBreak = Regex("</?(p|div|li|ul|ol|tr|td|th|dl|dt|dd|h[1-6]|br|section|article|table)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val genericLabel = Regex(
        "^(view report|cover note|read more|read|download|pdf|more|details?|view|자세히 ?보기|더 ?보기|바로 ?가기|바로 ?보기|원문 ?보기|다운로드|보기|상세 ?보기|첨부 ?파일?)$",
        RegexOption.IGNORE_CASE
    )

    private class Hit(val start: Int, val end: Int, val url: String, val title: String)
    private class DateHit(val date: LocalDate, val start: Int, val end: Int)

    fun anchorCount(html: String): Int = anchor.findAll(html).count()

    fun extract(html: String, baseUrl: String, hrefPattern: Regex, minTitleLength: Int = 4): List<ParsedLink> {
        val clean = noise.replace(html, " ")
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val hits = mutableListOf<Hit>()
        for (match in anchor.findAll(clean)) {
            val rawHref = href.find(match.groupValues[1])?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                ?: continue
            val decodedHref = decodeEntities(rawHref).trim()
            if (!hrefPattern.containsMatchIn(decodedHref)) continue
            val absolute = resolve(base, decodedHref) ?: continue
            val title = compact(decodeEntities(tag.replace(match.groupValues[2], " ")))
            hits += Hit(match.range.first, match.range.last + 1, absolute, title)
        }

        // 같은 링크가 아이콘·제목으로 여러 번 나올 수 있어 가장 긴 제목만 남긴다(순서는 처음 등장 기준).
        val byUrl = LinkedHashMap<String, Hit>()
        for (hit in hits) {
            val existing = byUrl[hit.url]
            if (existing == null || hit.title.length > existing.title.length) {
                byUrl[hit.url] = Hit(existing?.start ?: hit.start, hit.end, hit.url, hit.title)
            }
        }
        val ordered = byUrl.values.sortedBy { it.start }
        return ordered.mapIndexedNotNull { index, hit ->
            val prevEnd = ordered.getOrNull(index - 1)?.end ?: 0
            val title = if (isGeneric(hit.title)) contextTitle(clean, prevEnd, hit) else hit.title
            if (title.length < minTitleLength) return@mapIndexedNotNull null
            val nextStart = ordered.getOrNull(index + 1)?.start ?: clean.length
            ParsedLink(title, hit.url, dateFor(clean, hit, prevEnd, nextStart))
        }
    }


    private val textNode = Regex(">([^<]+)<")
    private val dotDate = Regex("(20\\d{2})\\s*[.-]\\s*(\\d{1,2})\\s*[.-]\\s*(\\d{1,2})")
    private val viewLink = Regex("""href\s*=\s*["']([^"']*flexer/view\?[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val downloadLink = Regex("""href\s*=\s*["']([^"']*common/downloadw\?[^"']*)["']""", RegexOption.IGNORE_CASE)

    /**
     * 자본시장연구원 보고서 목록. 한 항목이 "제목 → (직책) 저자 날짜 → 요약 → 바로보기/다운로드" 순으로 놓이므로,
     * 태그 구조에 기대지 않고 글 조각(text node)을 순서대로 읽어 "날짜로 끝나는 짧은 조각"을 항목의 기준점으로 삼는다.
     * 제목은 그 앞쪽에서 가장 가까운 긴 조각(요약이 아니라), 저자는 날짜 앞의 글, 링크는 다음 기준점 전까지의 첫 보고서 링크.
     */
    fun extractReports(html: String, baseUrl: String): List<ParsedLink> {
        val clean = noise.replace(html, " ")
        val base = runCatching { URI(baseUrl) }.getOrNull()
        class Node(val text: String, val start: Int, val end: Int)
        val nodes = textNode.findAll(clean).map { m ->
            val g = m.groups[1]!!
            Node(compact(decodeEntities(g.value)), g.range.first, g.range.last + 1)
        }.filter { it.text.isNotEmpty() }.toList()

        // 기준점: 날짜가 끝에 붙은 짧은 글(저자+날짜). 요약문 속 날짜는 길이로 거른다.
        val anchors = nodes.indices.filter { i ->
            val t = nodes[i].text
            val m = dotDate.findAll(t).lastOrNull() ?: return@filter false
            m.range.last >= t.length - 1 && t.length <= 40
        }
        return anchors.mapIndexedNotNull { n, i ->
            val node = nodes[i]
            val dm = dotDate.findAll(node.text).last()
            val date = runCatching { LocalDate.of(dm.groupValues[1].toInt(), dm.groupValues[2].toInt(), dm.groupValues[3].toInt()) }.getOrNull()
                ?: return@mapIndexedNotNull null
            var author = node.text.substring(0, dm.range.first).trim()
            var k = i - 1
            // 저자·직책이 날짜와 다른 조각에 있으면(짧은 글) 거슬러 올라가며 모은다.
            val parts = ArrayDeque<String>()
            if (author.isNotEmpty()) parts.addFirst(author)
            val prevAnchor = if (n > 0) anchors[n - 1] else -1
            while (k > prevAnchor && nodes[k].text.length < 12 && parts.size < 3 && !dotDate.containsMatchIn(nodes[k].text)) {
                parts.addFirst(nodes[k].text); k--
            }
            // 제목: 남은 앞쪽 조각 중 가장 가까운 제목다운(8자 이상, 안내 문구 아님) 글.
            var t = k
            var title = ""
            while (t > prevAnchor && t >= 0) {
                val c = nodes[t].text
                if (c.length >= 8 && !genericLabel.matches(c)) { title = c; break }
                t--
            }
            if (title.isEmpty()) return@mapIndexedNotNull null
            author = parts.joinToString(" ").trim()
            val nextStart = anchors.getOrNull(n + 1)?.let { nodes[it].start } ?: clean.length
            val region = clean.substring(node.end, nextStart)
            val reportView = viewLink.findAll(region).map { decodeEntities(it.groupValues[1]) }.toList()
            val pick = reportView.firstOrNull { it.contains("fty=004003") }
                ?: downloadLink.findAll(region).map { decodeEntities(it.groupValues[1]) }.firstOrNull { it.contains("fty=004003") }
                ?: reportView.firstOrNull()
                ?: downloadLink.find(region)?.let { decodeEntities(it.groupValues[1]) }
            val url = pick?.let { resolve(base, it) } ?: baseUrl
            ParsedLink(title, url, date, author.ifEmpty { null })
        }.distinctBy { it.url + it.title }
    }

    /** RSS 2.0 `<item>`에서 제목·링크·발행일을 읽는다. */
    fun parseRss(xml: String, baseUrl: String): List<ParsedLink> {
        val itemRegex = Regex("<item\\b.*?</item>", options)
        return itemRegex.findAll(xml).mapNotNull { item ->
            val body = item.value
            val title = compact(decodeEntities(tag.replace(unwrapCdata(field(body, "title")), " ")))
            val link = compact(unwrapCdata(field(body, "link")))
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            val absolute = resolve(runCatching { URI(baseUrl) }.getOrNull(), decodeEntities(link)) ?: return@mapNotNull null
            ParsedLink(title, absolute, parseRssDate(field(body, "pubDate")))
        }.toList()
    }

    fun decodeEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        out = numericEntity.replace(out) { m ->
            val code = m.groupValues[1]
            val value = if (code.startsWith("x", true)) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            if (value != null && value in 1..0x10FFFF) String(Character.toChars(value)) else m.value
        }
        return out.replace("&amp;", "&")
    }

    fun compact(text: String): String = text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun isGeneric(title: String) = title.isBlank() || genericLabel.matches(title)

    private fun isTitleLike(text: String) =
        text.length >= 6 && !genericLabel.matches(text) && findDates(text).firstOrNull()?.let { it.start == 0 && it.end >= text.length - 1 } != true

    /** 링크 글자가 제목이 아닐 때: 링크 앞쪽의 굵은 글, 없으면 첫 의미 있는 글줄을 제목으로 본다. */
    private fun contextTitle(clean: String, from: Int, hit: Hit): String {
        val windowHtml = clean.substring(maxOf(from, hit.start - 1200), hit.start)
        val bold = boldBlock.findAll(windowHtml)
            .map { compact(decodeEntities(tag.replace(it.groupValues[2], " "))) }
            .filter(::isTitleLike)
            .lastOrNull()
        if (bold != null) return bold
        // 굵은 글이 없으면 같은 행(<tr>/<li>) 안에서 가장 긴 글줄(보통 제목)을 쓴다.
        val rowStart = rowOpen.findAll(windowHtml).lastOrNull()?.range?.first ?: 0
        return tag.replace(blockBreak.replace(windowHtml.substring(rowStart), "\n"), " ")
            .split("\n")
            .map { compact(decodeEntities(it)) }
            .filter(::isTitleLike)
            .maxByOrNull { it.length }
            .orEmpty()
    }

    /**
     * 링크 주소 패턴을 모를 때의 대안: 같은 행에 날짜가 있고 글자가 제목답게 긴 링크를 목록으로 본다.
     * 주소를 만들 수 없는 링크(javascript: 등)는 목록 페이지 주소로 대신한다.
     */
    fun extractLoose(html: String, baseUrl: String): List<ParsedLink> {
        val clean = noise.replace(html, " ")
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val found = LinkedHashMap<String, ParsedLink>()
        for (match in anchor.findAll(clean)) {
            val text = compact(decodeEntities(tag.replace(match.groupValues[2], " ")))
            if (text.length < 8 || genericLabel.matches(text) || findDates(text).isNotEmpty()) continue
            val hit = Hit(match.range.first, match.range.last + 1, "", text)
            val date = rowDate(clean, hit) ?: continue
            val rawHref = href.find(match.groupValues[1])?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()
            val url = resolve(base, decodeEntities(rawHref).trim()) ?: baseUrl
            found.putIfAbsent(text, ParsedLink(text, url, date))
        }
        return found.values.toList()
    }

    /**
     * 주소 패턴을 모르는 임의의 목록 페이지용. 날짜가 붙은 링크가 있으면 그것을, 없으면
     * 머리말·메뉴·꼬리말을 뺀 같은 사이트 안의 긴 제목 링크를 앞에서부터 쓴다.
     */
    fun extractGeneric(html: String, baseUrl: String): List<ParsedLink> {
        val dated = extractLoose(html, baseUrl)
        if (dated.isNotEmpty()) return dated
        val clean = noise.replace(html, " ").replace(Regex("<(nav|header|footer|aside)\\b.*?</\\1>", options), " ")
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        val found = LinkedHashMap<String, ParsedLink>()
        for (match in anchor.findAll(clean)) {
            val text = compact(decodeEntities(tag.replace(match.groupValues[2], " ")))
            if (text.length < 10 || genericLabel.matches(text)) continue
            val rawHref = href.find(match.groupValues[1])?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: continue
            val url = resolve(base, decodeEntities(rawHref).trim()) ?: continue
            val host = runCatching { URI(url).host }.getOrNull()
            if (host == null || host.removePrefix("www.") != base.host?.removePrefix("www.")) continue
            if (url.substringBefore('#') == baseUrl.substringBefore('#')) continue
            found.putIfAbsent(url, ParsedLink(text, url, null))
            if (found.size >= 30) break
        }
        return found.values.toList()
    }

    /** 오류 안내에 붙일 진단용 샘플: 글자가 긴 링크 3개의 (글자→주소 앞부분). */
    fun sampleLinks(html: String): String = anchor.findAll(noise.replace(html, " "))
        .mapNotNull { m ->
            val text = compact(decodeEntities(tag.replace(m.groupValues[2], " ")))
            if (text.length < 8) return@mapNotNull null
            val target = href.find(m.groupValues[1])?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()
            "${text.take(12)}→${target.take(40)}"
        }
        .take(3).joinToString(" / ")

    /** 같은 행(<tr>/<li>)의 날짜를 우선하고, 행 구조가 없으면 링크에서 더 가까운 쪽(앞/뒤)의 날짜를 쓴다. */
    private fun dateFor(clean: String, hit: Hit, prevEnd: Int, nextStart: Int): LocalDate? {
        rowDate(clean, hit)?.let { return it }
        val afterText = clean.substring(hit.end, minOf(nextStart, hit.end + 600))
        val beforeText = clean.substring(maxOf(prevEnd, hit.start - 300), hit.start)
        val after = findDates(afterText).firstOrNull()
        val before = findDates(beforeText).lastOrNull()
        return when {
            after == null -> before?.date
            before == null -> after.date
            beforeText.length - before.end < after.start -> before.date
            else -> after.date
        }
    }

    private fun rowDate(clean: String, hit: Hit): LocalDate? {
        val windowStart = maxOf(0, hit.start - 1500)
        val open = rowOpen.findAll(clean.substring(windowStart, hit.start)).lastOrNull() ?: return null
        val close = rowClose.find(clean, hit.end) ?: return null
        if (close.range.first - hit.end > 1500) return null
        val row = clean.substring(windowStart + open.range.first, hit.start) + " " + clean.substring(hit.end, close.range.first)
        return findDates(row).firstOrNull()?.date
    }

    /** 숫자형(2026.09.04)과 영문형(08 Sep 2026) 날짜를 등장 순서대로 찾는다. */
    private fun findDates(text: String): List<DateHit> {
        val found = mutableListOf<DateHit>()
        for (m in numericDate.findAll(text)) {
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                .getOrNull()?.let { found += DateHit(it, m.range.first, m.range.last + 1) }
        }
        for (m in englishDate.findAll(text)) {
            val month = months.indexOf(m.groupValues[2].lowercase(Locale.ROOT)) + 1
            runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }
                .getOrNull()?.let { found += DateHit(it, m.range.first, m.range.last + 1) }
        }
        if (found.isEmpty()) {
            for (m in monthOnlyDate.findAll(text)) {
                runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1) }
                    .getOrNull()?.let { found += DateHit(it, m.range.first, m.range.last + 1) }
            }
        }
        return found.sortedBy { it.start }
    }

    private fun field(body: String, name: String): String =
        Regex("<$name\\b[^>]*>(.*?)</$name>", options).find(body)?.groupValues?.get(1).orEmpty()

    private fun unwrapCdata(text: String): String =
        Regex("<!\\[CDATA\\[(.*?)]]>", options).find(text)?.groupValues?.get(1) ?: text

    private fun parseRssDate(value: String): LocalDate? {
        val text = compact(unwrapCdata(value))
        if (text.isBlank()) return null
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate() }
            .recoverCatching { ZonedDateTime.parse(text, DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)).toLocalDate() }
            .getOrNull() ?: findDates(text).firstOrNull()?.date
    }

    private fun resolve(base: URI?, link: String): String? {
        if (link.isBlank() || link.startsWith("javascript:", true) || link.startsWith("#")) return null
        return runCatching {
            val target = URI(link.replace(" ", "%20"))
            val absolute = if (target.isAbsolute) target else base?.resolve(target) ?: return null
            absolute.toString().takeIf { absolute.scheme == "http" || absolute.scheme == "https" }
        }.getOrNull()
    }
}
