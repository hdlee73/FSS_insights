package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataTest {
    @Test
    fun ordersPanelBySavedSymbolsAndKeepsTheRest() {
        val all = (MARKET_PANEL + COMMODITY_PANEL).map { it.symbol }
        assertEquals(all, orderedPanel(emptyList()).map { it.symbol })
        val result = orderedPanel(listOf("GC=F", "unknown", "^KS11")).map { it.symbol }
        assertEquals(listOf("GC=F", "^KS11"), result.take(2))
        assertEquals(all.sorted(), result.sorted())
    }

    @Test
    fun parsesGroupedNumbers() {
        assertEquals(1234567.5, parseNumber("1,234,567.5"), 0.0)
    }

    @Test
    fun appliesNaverDirectionCode() {
        assertEquals(-30.0, signedChange(30.0, "5"), 0.0)
        assertEquals(30.0, signedChange(-30.0, "2"), 0.0)
        assertEquals(0.0, signedChange(5.0, "3"), 0.0)
    }

    @Test
    fun quoteChangeUsesPreviousClose() {
        val q = Quote(110.0, 100.0, 0, "")
        assertEquals(10.0, q.change!!, 1e-9)
        assertEquals(10.0, q.changePercent!!, 1e-9)
        assertEquals(null, Quote(110.0, null, 0, "").change)
    }

    @Test
    fun defaultWatchlistHasRequestedInstruments() {
        val names = DEFAULT_WATCH.map { it.name }
        assertTrue(names.containsAll(listOf("삼성전자", "SK하이닉스", "KODEX 200")))
        assertTrue(DEFAULT_WATCH.any { it.symbol == "^SOX" })
    }

    @Test
    fun localSearchFindsIndexByAlias() {
        assertEquals("^SOX", searchLocal("필라델피아").first().symbol)
        assertTrue(searchLocal("kospi").any { it.symbol == "^KS11" })
    }
}
