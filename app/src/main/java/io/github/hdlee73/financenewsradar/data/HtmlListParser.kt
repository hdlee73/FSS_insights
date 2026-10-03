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
 */
object HtmlListParser {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val noise = Regex("<!--.*?-->|<(script|style|noscript)\\b.*?</\\1>", options)
    private val anchor = Regex("<a\\b([^>]*)>(.*?)</a>", options)
    private val href = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")
    private val date = Regex("(20\\d{2})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})")
    private val numericEntity = Regex("&#(x[0-9a-fA-F]+|\\d+);")
    private val rowOpen = Regex("<(tr|li)\\b", RegexOption.IGNORE_CASE)
    private val rowClose = Regex("</(tr|li)>", RegexOption.IGNORE_CASE)

    private class Hit(val start: Int, val end: Int, val url: String, val title: String)

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
            if (hit.title.length < minTitleLength) return@mapIndexedNotNull null
            // 같은 행(<tr>/<li>) 안의 날짜를 우선 사용하고, 행 구조가 없으면 링크 뒤→앞 순서로 가까운 날짜를 쓴다.
            val nextStart = ordered.getOrNull(index + 1)?.start ?: clean.length
            val prevEnd = ordered.getOrNull(index - 1)?.end ?: 0
            val after = clean.substring(hit.end, minOf(nextStart, hit.end + 600))
            val before = clean.substring(maxOf(prevEnd, hit.start - 300), hit.start)
            ParsedLink(hit.title, hit.url, rowDate(clean, hit) ?: findDate(after) ?: findDate(before))
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

    private fun rowDate(clean: String, hit: Hit): LocalDate? {
        val windowStart = maxOf(0, hit.start - 1500)
        val open = rowOpen.findAll(clean.substring(windowStart, hit.start)).lastOrNull() ?: return null
        val close = rowClose.find(clean, hit.end) ?: return null
        if (close.range.first - hit.end > 1500) return null
        val row = clean.substring(windowStart + open.range.first, hit.start) + " " + clean.substring(hit.end, close.range.first)
        return findDate(row)
    }

    private fun findDate(text: String): LocalDate? {
        val m = date.find(text) ?: return null
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
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
            .getOrNull() ?: findDate(text)
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
