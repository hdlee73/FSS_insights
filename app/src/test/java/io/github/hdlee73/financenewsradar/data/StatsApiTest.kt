package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsApiTest {
    @Test
    fun parsesItemsAndSkipsMissingValues() {
        val body = """{"items":[
            {"id":"base-rate","name":"한국은행 기준금리","unit":"%","value":2.5,"previous":2.75,"period":"20261007"},
            {"id":"x","name":"값 없음","unit":"","period":"202609"}],"missing":["y"]}"""
        val items = parseStats(body)
        assertEquals(1, items.size)
        assertEquals(-0.25, items[0].change!!, 1e-9)
        assertEquals("%", items[0].unit)
    }

    @Test
    fun changeIsNullWithoutPrevious() {
        val item = StatItem("a", "a", "", 10.0, null, "202609")
        assertNull(item.change)
        assertNull(item.changePercent)
    }

    @Test
    fun periodLabelsAreReadable() {
        assertEquals("10/07", periodLabel("20261007"))
        assertEquals("2026.09", periodLabel("202609"))
        assertEquals("2026 2분기", periodLabel("2026Q2"))
    }

    @Test
    fun catalogIdsAreUniqueAndGrouped() {
        val ids = STAT_CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("금리·채권", StatItem("ktb-3y", "x", "%", 1.0, null, "20261007").group)
        assertEquals("기타", StatItem("unknown", "x", "", 1.0, null, "202609").group)
    }

    @Test
    fun defaultsAreInCatalogAndBriefingKeepsTwoStats() {
        val ids = STAT_CATALOG.map { it.id }.toSet()
        assertTrue(ids.containsAll(DEFAULT_MARKET_STATS))
        assertEquals(listOf("ktb-3y", "corp-aa"), DEFAULT_BRIEFING_STATS)
    }

    @Test
    fun selectionRequestsUnionInCatalogOrder() {
        val selection = StatSelection(market = setOf("corp-aa", "base-rate"), briefing = setOf("ktb-3y", "corp-aa"))
        assertEquals(listOf("base-rate", "ktb-3y", "corp-aa"), selection.all)
    }

    @Test
    fun parsesMissingIdsFromServer() {
        val body = """{"items":[{"id":"ktb-3y","name":"국고채 3년","unit":"%","value":3.1,"previous":3.0,"period":"20261007"}],"missing":["ktb-5y","bank-mortgage"]}"""
        val result = parseStatsResult(body)
        assertEquals(1, result.items.size)
        assertEquals(setOf("ktb-5y", "bank-mortgage"), result.missing)
        assertTrue(parseStatsResult("""{"items":[]}""").missing.isEmpty())
    }

    @Test
    fun indexSelectionKeepsCatalogOrder() {
        val selection = IndexSelection(setOf("^N225", "^KS11", "^GSPC"), setOf("^KS11"))
        assertEquals(listOf("^KS11", "^GSPC", "^N225"), selection.marketItems.map { it.symbol })
        assertEquals(listOf("^KS11"), selection.briefingItems.map { it.symbol })
        assertEquals(setOf("^N225", "^KS11", "^GSPC"), selection.all)
    }
}
