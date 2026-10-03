package io.github.hdlee73.financenewsradar.data

import java.net.URI
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedLink(val title: String, val url: String, val date: LocalDate?)

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
