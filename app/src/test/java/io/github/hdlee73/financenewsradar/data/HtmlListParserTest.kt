package io.github.hdlee73.financenewsradar.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlListParserTest {
    private val fscPattern = Regex("""no010101/\d+""")

    @Test
    fun extractsTitleLinkAndDateFromBoardRows() {
        val html = """
            <ul>
              <li><div class="subject"><a href="/no010101/87869?curPage=1">최근 발생하는 금융권 침해위협에 &quot;면밀히&quot; 대응</a></div><div class="day">2026-10-02</div></li>
              <li><div class="subject"><a href="/no010101/87857">제3회 금융위人상 &amp; 포상</a></div><div class="day">2026-10-01</div></li>
              <li><a href="/no020101/1">다른 게시판 링크는 제외</a> 2026-09-30</li>
            </ul>
        """.trimIndent()
        val result = HtmlListParser.extract(html, "https://www.fsc.go.kr/no010101", fscPattern)
        assertEquals(2, result.size)
        assertEquals("최근 발생하는 금융권 침해위협에 \"면밀히\" 대응", result[0].title)
        assertEquals("https://www.fsc.go.kr/no010101/87869?curPage=1", result[0].url)
        assertEquals(LocalDate.of(2026, 10, 2), result[0].date)
        assertEquals("제3회 금융위人상 & 포상", result[1].title)
        assertEquals(LocalDate.of(2026, 10, 1), result[1].date)
    }

    @Test
    fun dateBeforeTitleIsNotStolenFromNextRow() {
        val html = """
            <tr><td>2026.09.04</td><td><a href="/flexer/view?fid=29149&amp;fgu=002002">첫 번째 보고서 제목</a></td></tr>
            <tr><td>2026.08.26</td><td><a href="/flexer/view?fid=29105&amp;fgu=002002">두 번째 보고서 제목</a></td></tr>
        """.trimIndent()
        val result = HtmlListParser.extract(html, "https://www.kcmi.re.kr/report/report_list", Regex("""flexer/view\?fid=\d+"""))
        assertEquals(2, result.size)
        assertEquals("https://www.kcmi.re.kr/flexer/view?fid=29149&fgu=002002", result[0].url)
        assertEquals(LocalDate.of(2026, 9, 4), result[0].date)
        assertEquals(LocalDate.of(2026, 8, 26), result[1].date)
    }

    @Test
    fun duplicateLinksKeepLongestTitleAndSkipShortOnes() {
        val html = """
            <a href="/no010101/1"><img src="x.png"></a>
            <a href="/no010101/1">충분히 긴 제목입니다</a>
            <a href="/no010101/2">더보기</a>
            <a href="/no010101/3">1</a>
        """.trimIndent()
        val result = HtmlListParser.extract(html, "https://www.fsc.go.kr/no010101", fscPattern)
        assertEquals(listOf("충분히 긴 제목입니다"), result.map { it.title })
    }

    @Test
    fun ignoresScriptsCommentsAndJavascriptLinks() {
        val html = """
            <script>var a = '<a href="/no010101/9">스크립트 안 링크</a>';</script>
            <!-- <a href="/no010101/8">주석 안 링크</a> -->
            <a href="javascript:fn_view(7)">자바스크립트 링크 제목</a>
            <a href="/no010101/6">진짜 게시물 제목</a>
        """.trimIndent()
        val result = HtmlListParser.extract(html, "https://www.fsc.go.kr/no010101", fscPattern)
        assertEquals(listOf("진짜 게시물 제목"), result.map { it.title })
        assertNull(result[0].date)
    }

    @Test
    fun parsesRssItemsWithCdataAndDates() {
        val xml = """
            <rss><channel>
              <item><title><![CDATA[Cyber Resilience Toolkit: FMIs]]></title><link>https://www.iosco.org/library/pubdocs/pdf/IOSCOPD829.pdf</link><pubDate>Tue, 08 Sep 2026 10:00:00 +0000</pubDate></item>
              <item><title>SupTech &amp; Mapping</title><link>https://www.iosco.org/library/pubdocs/pdf/IOSCOPD826.pdf</link></item>
            </channel></rss>
        """.trimIndent()
        val result = HtmlListParser.parseRss(xml, "https://www.iosco.org/rss/rss.xml")
        assertEquals(2, result.size)
        assertEquals("Cyber Resilience Toolkit: FMIs", result[0].title)
        assertEquals(LocalDate.of(2026, 9, 8), result[0].date)
        assertEquals("SupTech & Mapping", result[1].title)
        assertNull(result[1].date)
    }

    @Test
    fun genericLinkLabelUsesBoldTitleAndEnglishDate() {
        val html = """
            <div class="item"><b>Cyber Resilience Toolkit for FMIs</b><span>08 Sep 2026</span>
              <a href="/library/pubdocs/pdf/IOSCOPD829.pdf">View Report</a></div>
            <div class="item"><b>Suptech Mapping Report</b><span>1 December 2026 (consultation closes)</span><span>25 Aug 2026</span>
              <a href="/library/pubdocs/pdf/IOSCOPD826.pdf">View Report</a></div>
        """.trimIndent()
        val result = HtmlListParser.extract(html, "https://www.iosco.org/publications/", Regex("""(?i)pubdocs/pdf/IOSCOPD\d+\.pdf"""))
        assertEquals(2, result.size)
        assertEquals("Cyber Resilience Toolkit for FMIs", result[0].title)
        assertEquals(LocalDate.of(2026, 9, 8), result[0].date)
        assertEquals("Suptech Mapping Report", result[1].title)
        assertEquals(LocalDate.of(2026, 8, 25), result[1].date)
    }

    @Test
    fun searchMatchesEveryTokenIgnoringCase() {
        assertTrue(AgencySources.matches("퇴직연금 제도 개편 방안", "퇴직연금 개편"))
        assertTrue(AgencySources.matches("Cyber Resilience Toolkit", "cyber toolkit"))
        assertFalse(AgencySources.matches("퇴직연금 제도", "퇴직연금 ETF"))
        assertFalse(AgencySources.matches("아무 제목", "   "))
    }
}
