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
    fun viewButtonLabelFallsBackToLongestTextInRow() {
        val html = """
            <table>
            <tr><td>1</td><td>경제분석</td><td class="t">K-자본시장 정책시리즈 1: 초고령사회 다층노후소득을 위한 퇴직연금제도 개편</td><td>남재우</td><td>2026.09.04</td>
              <td><a href="/flexer/view?fid=29149&amp;fgu=002002">바로보기</a></td></tr>
            <tr><td>2</td><td>자본시장</td><td class="t">무형자산의 부상과 생산요소의 배분 효율성</td><td>정희철</td><td>2026.08.26</td>
              <td><a href="/flexer/view?fid=29105&amp;fgu=002002">바로보기</a></td></tr>
            </table>
        """.trimIndent()
        val result = HtmlListParser.extract(html, "https://www.kcmi.re.kr/report/report_list", Regex("""flexer/view\?fid=\d+"""))
        assertEquals(2, result.size)
        assertEquals("K-자본시장 정책시리즈 1: 초고령사회 다층노후소득을 위한 퇴직연금제도 개편", result[0].title)
        assertEquals("무형자산의 부상과 생산요소의 배분 효율성", result[1].title)
        assertEquals(LocalDate.of(2026, 8, 26), result[1].date)
    }

    @Test
    fun looseExtractionKeepsOnlyDatedTitleLikeLinks() {
        val html = """
            <ul><li><a href="/about">연구원 소개</a></li></ul>
            <ul><li><a href="/kif4/publication/detail?no=5">2026년 금융시장 전망과 정책 과제</a><span>2026-09-30</span></li></ul>
        """.trimIndent()
        val result = HtmlListParser.extractLoose(html, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10")
        assertEquals(1, result.size)
        assertEquals("https://www.kif.re.kr/kif4/publication/detail?no=5", result[0].url)
    }

    @Test
    fun kcmiRowPicksReportLinkNotPressReleaseLink() {
        val html = """
            <tr><td>경제분석</td><td>K-자본시장 정책시리즈 1: 초고령사회 다층노후소득을 위한 퇴직연금제도 개편</td><td>2026.09.04</td>
              <td><a href="/flexer/view?fid=29149&amp;fgu=002002&amp;fty=004010">바로보기</a>
                  <a href="/flexer/view?fid=29154&amp;fgu=002002&amp;fty=004003">바로보기</a>
                  <a href="/common/downloadw?fid=29154&amp;fgu=002002&amp;fty=004003">다운로드</a></td></tr>
        """.trimIndent()
        val result = HtmlListParser.extractReports(html, "https://www.kcmi.re.kr/report/report_list")
        assertEquals(1, result.size)
        assertEquals("https://www.kcmi.re.kr/flexer/view?fid=29154&fgu=002002&fty=004003", result[0].url)
        assertTrue(result[0].title.startsWith("K-자본시장 정책시리즈 1"))
        assertEquals(LocalDate.of(2026, 9, 4), result[0].date)
    }

    @Test
    fun looseExtractionFallsBackToListUrlForJavascriptLinks() {
        val html = """<ul><li><a href="javascript:fnView('77')">2026년 금융시장 전망과 정책 과제</a><span>2026.09.30</span></li></ul>"""
        val result = HtmlListParser.extractLoose(html, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10")
        assertEquals(1, result.size)
        assertEquals("https://www.kif.re.kr/kif4/publication/pub_list?mid=10", result[0].url)
    }

    @Test
    fun kifDetailLinksWithDatesAreListed() {
        val html = """
            <ul class="list">
              <li><a href="/kif4/publication/pub_detail?mid=10&amp;nid=867&amp;sid=867&amp;vid=7358&amp;cno=344155">가계부채 관리 방향과 금융안정 과제</a><span class="date">2026-09-19</span></li>
              <li><a href="/kif4/publication/pub_detail?mid=10&amp;nid=866&amp;sid=866&amp;vid=7357&amp;cno=344100">중소기업 금융 접근성 개선 방안</a><span class="date">2026-09-05</span></li>
            </ul>
        """.trimIndent()
        val source = AgencySources.of(io.github.hdlee73.financenewsradar.model.AgencyId.KIF)
        val result = HtmlListParser.extract(html, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10", source.linkPattern)
            .filter { it.date != null }
        assertEquals(2, result.size)
        assertEquals("https://www.kif.re.kr/kif4/publication/pub_detail?mid=10&nid=867&sid=867&vid=7358&cno=344155", result[0].url)
        assertEquals(LocalDate.of(2026, 9, 5), result[1].date)
    }

    @Test
    fun kifRelativeLinksWithYearMonthDatesAreListed() {
        val html = """
            <ul class="list">
              <li><a href="pub_detail?mid=10&amp;nid=867&amp;vid=7358">가계부채 관리 방향과 금융안정 과제</a><span class="date">2026-09</span></li>
              <li><a href="pub_detail?mid=10&amp;nid=866&amp;vid=7357">중소기업 금융 접근성 개선 방안</a><span class="date">2026-08</span></li>
              <li><a href="pub_list?mid=10">목록으로 돌아가기 메뉴</a></li>
            </ul>
        """.trimIndent()
        val source = AgencySources.of(io.github.hdlee73.financenewsradar.model.AgencyId.KIF)
        val result = HtmlListParser.extract(html, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10", source.linkPattern)
            .filter { it.date != null }.sortedByDescending { it.date }
        assertEquals(2, result.size)
        assertEquals("https://www.kif.re.kr/kif4/publication/pub_detail?mid=10&nid=867&vid=7358", result[0].url)
        assertEquals(LocalDate.of(2026, 9, 1), result[0].date)
    }

    @Test
    fun genericExtractionFallsBackToSameSiteTitleLinksWithoutDates() {
        val html = """
            <header><a href="/about/intro-page">연구원 소개 페이지 안내문</a></header>
            <ul>
              <li><a href="/reports/101">가계부채 관리 방향과 금융안정 과제</a></li>
              <li><a href="/reports/102">중소기업 금융 접근성 개선 방안</a></li>
              <li><a href="https://other.example.com/x">외부 사이트의 긴 제목 링크 하나</a></li>
              <li><a href="/reports/103">더보기</a></li>
            </ul>
        """.trimIndent()
        val result = HtmlListParser.extractGeneric(html, "https://www.example.re.kr/reports")
        assertEquals(listOf("https://www.example.re.kr/reports/101", "https://www.example.re.kr/reports/102"), result.map { it.url })
    }

    @Test
    fun searchMatchesEveryTokenIgnoringCase() {
        assertTrue(AgencySources.matches("퇴직연금 제도 개편 방안", "퇴직연금 개편"))
        assertTrue(AgencySources.matches("Cyber Resilience Toolkit", "cyber toolkit"))
        assertFalse(AgencySources.matches("퇴직연금 제도", "퇴직연금 ETF"))
        assertFalse(AgencySources.matches("아무 제목", "   "))
    }

    @Test
    fun kcmiReportsUseTitleAuthorAndDateNotSummary() {
        val html = """
            <div class="item"><h3><strong>K-자본시장 정책시리즈 2: 자본시장 토큰화의 확산: 해외 주요 사례와 국내 도입 과제</strong>
              <em>연구위원 정화영 2026.10.06</em></h3>
              <p>분산원장기술 기반의 토큰화를 활용한 상품과 관련 서비스가 점진적으로 늘어나면서, 금융기관과 정책당국의 주요 관심 사항으로 떠오르고 있다.</p>
              <a href="/flexer/view?fid=29203&amp;fgu=002002&amp;fty=004010">바로보기</a>
              <a href="/common/downloadw?fid=29201&amp;fgu=002002&amp;fty=004003">다운로드</a></div>
            <div class="item"><h3><strong>무형자산의 부상과 생산요소의 배분 효율성</strong>
              <em>연구위원 정희철 2026.08.26</em></h3>
              <p>2024년 12월 우리나라는 초고령사회에 진입하였다.</p>
              <a href="/flexer/view?fid=29105&amp;fgu=002002&amp;fty=004003">바로보기</a></div>
        """.trimIndent()
        val result = HtmlListParser.extractReports(html, "https://www.kcmi.re.kr/report/report_list")
        assertEquals(2, result.size)
        assertTrue(result[0].title.startsWith("K-자본시장 정책시리즈 2"))
        assertEquals("연구위원 정화영", result[0].author)
        assertEquals(LocalDate.of(2026, 10, 6), result[0].date)
        assertEquals("https://www.kcmi.re.kr/common/downloadw?fid=29201&fgu=002002&fty=004003", result[0].url)
        assertEquals("무형자산의 부상과 생산요소의 배분 효율성", result[1].title)
        assertEquals("https://www.kcmi.re.kr/flexer/view?fid=29105&fgu=002002&fty=004003", result[1].url)
    }

    @Test
    fun kcmiReportsHandleTableRowsWithSeparateAuthorCell() {
        val html = """
            <tr><td>경제분석</td><td class="t">K-자본시장 정책시리즈 1: 초고령사회 다층노후소득을 위한 퇴직연금제도 개편</td><td>남재우</td><td>2026.09.04</td>
              <td><a href="/flexer/view?fid=29149&amp;fgu=002002&amp;fty=004003">바로보기</a></td></tr>
        """.trimIndent()
        val result = HtmlListParser.extractReports(html, "https://www.kcmi.re.kr/report/report_list")
        assertEquals(1, result.size)
        assertTrue(result[0].title.startsWith("K-자본시장 정책시리즈 1"))
        assertEquals("남재우", result[0].author)
    }
}
