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

    private val seoul = java.time.ZoneId.of("Asia/Seoul")
    private fun day(date: String, vararg closes: Double) = SessionDay(java.time.LocalDate.parse(date), closes.toList(), closes.first())
    private fun epoch(local: String) = java.time.LocalDateTime.parse(local).atZone(seoul).toEpochSecond()

    @Test
    fun compareSeriesLiveShowsTodayWithPreviousDayAndAnchorsPrice() {
        val days = listOf(day("2026-10-30", 5700.0, 5710.0, 5730.74), day("2026-11-02", 5640.0, 5700.0, 5750.0))
        val s = buildCompare(days, seoul, epoch("2026-11-02T09:35:00"), 391, false, true, 5730.74, 5740.06, epoch("2026-11-02T09:39:00"))
        assertEquals("장중", s.status)
        assertTrue(s.live)
        assertEquals(5740.06, s.points.last(), 0.0)
        assertEquals(listOf(5700.0, 5710.0, 5730.74), s.prevPoints)
        assertEquals(5730.74, s.previous!!, 0.0)
    }

    @Test
    fun compareSeriesBeforeOpenWeekendAndHolidayLabels() {
        val days = listOf(day("2026-10-29", 1.0, 2.0), day("2026-10-30", 3.0, 4.0))
        val lastBar = epoch("2026-10-30T15:30:00")
        val pre = buildCompare(days, seoul, lastBar, 391, false, true, 3.0, 4.0, epoch("2026-11-02T08:40:00"))
        assertEquals("개장 전", pre.status)
        val weekend = buildCompare(days, seoul, lastBar, 391, false, true, 3.0, 4.0, epoch("2026-11-01T11:00:00"))
        assertEquals("휴장 · 10/30 마감", weekend.status)
        assertEquals(false, weekend.live)
        assertEquals(4.0, weekend.points.last(), 0.0)
        val closed = buildCompare(days, seoul, lastBar, 391, false, true, 3.0, 4.0, epoch("2026-10-30T16:30:00"))
        assertEquals("마감", closed.status)
    }

    @Test
    fun compareSeriesForeignMarketAndTwentyFourHour() {
        val ny = java.time.ZoneId.of("America/New_York")
        val days = listOf(day("2026-10-29", 1.0, 2.0), day("2026-10-30", 3.0, 4.0))
        val us = buildCompare(days, ny, java.time.LocalDateTime.parse("2026-10-30T16:00:00").atZone(ny).toEpochSecond(), 390, false, false, 2.0, 4.0, epoch("2026-11-02T09:00:00"))
        assertEquals("마감 10/30(미국)", us.status)
        val fx = buildCompare(days, ny, epoch("2026-11-02T09:00:00"), 288, true, false, 2.0, 4.0, epoch("2026-11-02T09:05:00"))
        assertEquals(null, fx.status)
        assertTrue(fx.live)
    }
}
