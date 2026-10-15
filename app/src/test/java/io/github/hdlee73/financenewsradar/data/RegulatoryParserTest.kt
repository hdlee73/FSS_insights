package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.RegItem
import io.github.hdlee73.financenewsradar.model.RegSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegulatoryParserTest {
    // 실제 사이트 마크업이 아니라 표 형태 게시판을 가정한 샘플이다(사이트 접속 없이 작성).
    private val html = """
        <table><thead><tr><th>번호</th><th>제목</th><th>시행일</th><th>시행여부</th></tr></thead>
        <tbody>
        <tr><td>2</td><td><a href="/view.do?id=2">전자금융 이용자 보호를 위한 행정지도</a></td><td>2026-03-01</td><td>시행중</td></tr>
        <tr><td>1</td><td><a href="javascript:fnView('1')">보험업 감독 관련 행정지도 (폐지)</a></td><td>2024.01.15</td><td>폐지</td></tr>
        </tbody></table>
    """.trimIndent()

    @Test
    fun parsesTableRowsByHeaderNames() {
        val rows = RegulatoryParser.parseList(html, "https://example.kr/list.do?x=1")
        assertEquals(2, rows.size)
        assertEquals("전자금융 이용자 보호를 위한 행정지도", rows[0].title)
        assertEquals("https://example.kr/view.do?id=2", rows[0].link)
        assertEquals(LocalDate.of(2026, 3, 1), rows[0].date)
        assertEquals(true, RegulatoryParser.statusOf(rows[0].statusText))
        assertNull(rows[1].link)
        assertEquals(LocalDate.of(2024, 1, 15), rows[1].date)
        assertEquals(false, RegulatoryParser.statusOf(rows[1].statusText))
    }

    @Test
    fun statusNeedsKnownWords() {
        assertEquals(true, RegulatoryParser.statusOf("시행 중"))
        assertEquals(false, RegulatoryParser.statusOf("시행종료"))
        assertNull(RegulatoryParser.statusOf("검토"))
    }

    @Test
    fun searchesTitleAndBody() {
        val item = RegItem(RegSource.NO_ACTION, "비조치의견서 회신", "u", null, "가상자산 사업자의 신고 의무에 관한 내용")
        assertTrue(RegulatoryParser.matches(item, "비조치 가상자산"))
        assertFalse(RegulatoryParser.matches(item, "가상자산 보험"))
        assertNotNull(RegulatoryParser.snippet(item, "신고"))
        assertNull(RegulatoryParser.snippet(item, "비조치"))
    }

    @Test
    fun detailTextStartsAtTitleAndReadsFields() {
        val page = "<div>메뉴 홈 로그인</div><h3>전자금융 이용자 보호 지침</h3><p>시행일: 2026.03.01</p><p>시행여부: 시행중</p>"
        val text = RegulatoryParser.htmlToText(page, "전자금융 이용자 보호 지침")
        assertTrue(text.startsWith("전자금융 이용자 보호 지침"))
        val (date, status) = RegulatoryParser.detailFields(text)
        assertEquals(LocalDate.of(2026, 3, 1), date)
        assertEquals("시행중", status)
    }
}
