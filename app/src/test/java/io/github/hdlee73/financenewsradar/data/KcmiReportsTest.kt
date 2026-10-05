package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class KcmiReportsTest {
    @Test
    fun parsesJsonItems() {
        val json = """[{"report_no":"2263","report_title":"국경간 암호화 자산 거래 현황 및 시사점","pub_date":"2026.02.26",
            "report_pdf_preview_link":"\/flexer\/view?fid=28755&fgu=002002&fty=004003"},
            {"report_no":"1","report_title":"","pub_date":"2026.01.01","report_pdf_preview_link":"/x"}]"""
        val items = KcmiReports.parse(json)
        assertEquals(1, items.size)
        assertEquals("https://www.kcmi.re.kr/flexer/view?fid=28755&fgu=002002&fty=004003", items[0].url)
        assertEquals(LocalDate.of(2026, 2, 26), items[0].date)
    }
}
