package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
