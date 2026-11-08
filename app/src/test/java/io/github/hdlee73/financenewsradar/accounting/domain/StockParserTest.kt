package io.github.hdlee73.financenewsradar.accounting.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockParserTest {
    private val sample = """ [['날짜', '시가', '고가', '저가', '종가', '거래량', '외국인소진율'],

["20251201", 102000, 102800, 99900, 100800, 10, 52.14],
["20251202", 101200, 103500, 101000, 103400, 20, 52.22],
["20251203", 1, 1, 1, 1, 0, 1.0]
]"""

    @Test fun parsesRows_ignoringHeader() {
        val c = StockParser.candles(sample)
        assertEquals(3, c.size)
        assertEquals(100800L, c[0].close)
        assertEquals(20L, c[1].volume)
    }

    @Test fun average_excludesZeroVolumeDays() {
        val c = StockParser.candles(sample)
        assertEquals((100800L * 10 + 103400L * 20) / 2, StockParser.avgTradingWon(c))
        assertNull(StockParser.avgTradingWon(emptyList()))
    }

    @Test fun marketMapping() {
        assertEquals("코스닥", StockParser.market("코스닥"))
        assertEquals("유가증권", StockParser.market("코스피"))
        assertEquals(CompanyKind.KONEX, StockParser.kindFor("코넥스"))
    }
}

class StockParserYmdTest {
    @org.junit.Test fun ymd() {
        org.junit.Assert.assertEquals("20250101", StockParser.ymd("2025-01-01"))
        org.junit.Assert.assertEquals("20251231", StockParser.ymd("20251231"))
        org.junit.Assert.assertEquals(null, StockParser.ymd("2025-02-30"))
        org.junit.Assert.assertEquals(null, StockParser.ymd("2025-1-1"))
        org.junit.Assert.assertEquals("2025-01-01", StockParser.dash("20250101"))
    }
}
