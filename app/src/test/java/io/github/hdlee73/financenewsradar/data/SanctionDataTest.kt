package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.YearMonth
import java.util.zip.ZipInputStream

class SanctionDataTest {
    private val json = """
        {"parser":1,"generatedAt":"2026-11-04T18:00+09:00","from":2016,"cases":[
         {"id":"202400144-2","co":"DB손해보험주식회사","date":"2026-10-26","dept":"보험검사2국","sector":"보험",
          "view":"https://www.fss.or.kr/v","file":"https://www.fss.or.kr/f.pdf",
          "inst":"과징금 39백만원, 과태료 105백만원","exec":"주의 1명","staff":"자율처리필요사항 3건",
          "instK":["과징금","과태료"],"execK":["주의"],"staffK":["자율처리"],"fine":105000000,"penalty":39000000,
          "items":[{"t":"보험계약의 설명의무 등 위반","cat":"문책","laws":["보험업법 제95조의2"]},
                   {"t":"기초서류 기재사항 준수의무 위반","cat":"문책","laws":["보험업법 제127조의3"]}],"ok":true},
         {"id":"202500199-5","co":"농협은행주식회사","date":"2025-10-26","dept":"자금세탁방지실","sector":"은행",
          "view":"https://www.fss.or.kr/v2","file":null,"inst":"기관경고","exec":"","staff":"감봉 6명, 견책 7명",
          "instK":["기관경고"],"execK":[],"staffK":["감봉","견책"],"fine":0,"penalty":0,"items":[],"ok":false,"why":"missing"}
        ]}
    """.trimIndent()

    @Test
    fun parsesAndFlattensToRows() {
        val d = SanctionJson.parse(json)
        assertEquals(2, d.cases.size)
        val rows = SanctionStats.rows(d.cases)
        assertEquals(3, rows.size)
        assertEquals("지적사항 미분류 · 금감원 사이트에 첨부 파일 없음(사이트 오류)", rows.last().title)
        assertTrue(rows.last().case.whyText().contains("해당 파일이 존재하지 않습니다"))
        assertEquals(listOf(2026, 2025), d.years)
    }

    @Test
    fun filtersByYearSectorAndSearchField() {
        val d = SanctionJson.parse(json)
        assertEquals(2, SanctionStats.filter(d, SanctionFilter(sector = "보험")).size)
        assertEquals(1, SanctionStats.filter(d, SanctionFilter().withYear(2025)).size)
        assertEquals(3, SanctionStats.filter(d, SanctionFilter(from = YearMonth.of(2025, 10), to = YearMonth.of(2026, 10))).size)
        assertEquals(0, SanctionStats.filter(d, SanctionFilter(from = YearMonth.of(2025, 11), to = YearMonth.of(2026, 9))).size)
        assertEquals(2, SanctionStats.filter(d, SanctionFilter(query = "보험업법", field = SanctionSearchField.LAW)).size)
        assertEquals(0, SanctionStats.filter(d, SanctionFilter(query = "보험업법", field = SanctionSearchField.COMPANY)).size)
        assertEquals(1, SanctionStats.filter(d, SanctionFilter(query = "설명의무", field = SanctionSearchField.TITLE)).size)
        assertEquals(1, SanctionStats.filter(d, SanctionFilter(query = "농협", field = SanctionSearchField.COMPANY)).size)
        assertEquals(2, SanctionStats.cases(SanctionStats.filter(d, SanctionFilter())).size)
        assertEquals(1, SanctionStats.filter(d, SanctionFilter(query = "보험업법 제95조의2")).size)
        assertEquals(2, SanctionStats.filter(d, SanctionFilter(query = "DB손해")).size)
    }

    @Test
    fun summaryCountsMoneyOncePerCase() {
        val d = SanctionJson.parse(json)
        val s = SanctionStats.summarize(SanctionStats.rows(d.cases), d.years)
        assertEquals(2, s.caseCount)
        assertEquals(2, s.itemCount)
        assertEquals(105_000_000L, s.fine)
        assertEquals(39_000_000L, s.penalty)
        assertEquals("보험업법" to 2, s.topLawNames.first())
    }

    @Test
    fun periodSummaryAndLabels() {
        val d = SanctionJson.parse(json)
        val f = SanctionFilter(from = YearMonth.of(2025, 9), to = YearMonth.of(2026, 10))
        val s = SanctionStats.summarize(SanctionStats.filter(d, f), d.years, f)
        assertEquals(14, s.byMonth.size)
        assertEquals(1, s.byMonth.first { it.first == YearMonth.of(2025, 10) }.second)
        assertEquals("2025.09~2026.10", f.periodLabel(2016))
        assertEquals("2025년", SanctionFilter().withYear(2025).periodLabel(2016))
        assertEquals("2016년 이후 전체", SanctionFilter().periodLabel(2016))
        assertEquals(SanctionPeriod.ALL, SanctionFilter().preset())
    }

    @Test
    fun formatsWon() {
        assertEquals("17억 3,050만원", SanctionStats.won(1_730_500_000))
        assertEquals("3,900만원", SanctionStats.won(39_000_000))
        assertEquals("-", SanctionStats.won(0))
    }

    @Test
    fun writesValidXlsxZip() {
        val d = SanctionJson.parse(json)
        val rows = SanctionStats.rows(d.cases)
        val out = ByteArrayOutputStream()
        SanctionExport.write(out, rows, SanctionStats.summarize(rows, d.years), "전체", d.generatedAt)
        val names = mutableListOf<String>()
        var sheet1 = ""
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                val text = z.readBytes().toString(Charsets.UTF_8)
                if (e.name == "xl/worksheets/sheet1.xml") sheet1 = text
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/styles.xml")))
        assertTrue(sheet1.contains("보험업법 제95조의2"))
        assertTrue(!sheet1.contains("<v>105000000</v>"))
        // 같은 공시(DB손해보험, 2행)의 기관·임원·직원 조치 칸은 세로로 합친다
        assertTrue(sheet1.contains("""<mergeCell ref="E2:E3"/>"""))
        assertTrue(sheet1.contains("""<mergeCell ref="G2:G3"/>"""))
        assertTrue(!sheet1.contains("공시 페이지") && !sheet1.contains("지적구분"))
        assertEquals("AA", XlsxWriter.col(26))
    }

    @Test
    fun cleansActionText() {
        assertEquals(listOf("기관경고", "과징금 11,196백만원"), SanctionText.actions("· 기관경고 / · 과징금 11,196백만원"))
        assertEquals(
            listOf("정직3월 1명", "퇴직자 위법·부당사항(주의 상당) 1명", "※ 미등기임원 포함"),
            SanctionText.actions("* / (3명) / · 정직3월 1명 / · 퇴직자 위법· / 부당사항(주의 상당) 1명 / * 미등기임원 포함")
        )
        assertEquals(listOf("퇴직자 위법·부당사항(주의적경고 상당) 1명"), SanctionText.actions("퇴직자 위법·부당사항(주의적경고 / 상당) 1명"))
    }

    @Test
    fun matchesCompanyNames() {
        val d = SanctionJson.parse(json)
        assertEquals(1, SanctionNames.casesOf(d, "농협은행").size)
        assertEquals(1, SanctionNames.casesOf(d, "DB손해보험").size)
        assertEquals(0, SanctionNames.casesOf(d, "국민은행").size)
    }
}
