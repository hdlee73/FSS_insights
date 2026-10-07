package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataTest {
    @Test
    fun panelHasSixDistinctSlotsAndDefaultsWhenNothingSaved() {
        assertEquals(listOf("^KS11", "^KQ11"), KOREA_INDEXES.map { it.symbol })
        val defaults = panelSlots(emptyList()).map { it.symbol }
        assertEquals(DEFAULT_PANEL_SLOTS, defaults)
        assertEquals(PANEL_SLOT_COUNT, defaults.distinct().size)
        assertEquals(PANEL_CATALOG.size, PANEL_CATALOG.map { it.symbol }.distinct().size)
    }

    @Test
    fun savedPanelChoiceKeepsOrderDropsUnknownAndFillsTheRest() {
        val result = panelSlots(listOf("^N225", "unknown", "^SOX", "^N225")).map { it.symbol }
        assertEquals(listOf("^N225", "^SOX"), result.take(2))
        assertEquals(PANEL_SLOT_COUNT, result.size)
        assertEquals(PANEL_SLOT_COUNT, result.distinct().size)
    }

    @Test
    fun catalogIncludesRequestedGlobalIndicators() {
        val symbols = PANEL_CATALOG.map { it.symbol }
        assertTrue(symbols.containsAll(listOf("^SOX", "^N225", "^HSI", "^DJI", "^VIX", "DX-Y.NYB", "^STOXX50E", "000001.SS")))
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
        assertTrue(DEFAULT_WATCH.none { it.symbol == "^SOX" })
    }

    @Test
    fun localSearchFindsIndexByAlias() {
        assertEquals("^SOX", searchLocal("필라델피아").first().symbol)
        assertTrue(searchLocal("kospi").any { it.symbol == "^KS11" })
    }
}
