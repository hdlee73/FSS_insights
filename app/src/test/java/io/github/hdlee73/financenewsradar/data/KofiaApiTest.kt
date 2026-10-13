package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KofiaApiTest {
    private val groups = parseKofia(javaClass.getResource("/kofia_sample.json")!!.readText())

    @Test
    fun readsFundAndMoneyFlowGroups() {
        assertEquals(listOf("펀드", "자금동향"), groups.map { it.title })
        assertEquals(listOf("펀드시장규모", "주요 유형별 규모", "자금유출입", "신규펀드", "평균보수"), groups[0].sections.map { it.title })
        assertEquals(listOf("증시자금", "신용공여", "CMA"), groups[1].sections.map { it.title })
    }

    @Test
    fun parsesRows() {
        val first = groups[0].sections[0]
        assertEquals("26/10/12", first.asOf)
        assertEquals("공모펀드 순자산총액", first.items[0].name)
        assertEquals("8,407,106", first.items[0].value)
        assertEquals(-157158.0, first.items[0].change!!, 1e-9)
        assertEquals(-1.84, first.items[0].changePercent!!, 1e-9)
        val deposit = groups[1].sections[0].items[0]
        assertEquals("투자자예탁금", deposit.name)
        assertEquals("백만원", groups[1].sections[0].unit)
    }

    @Test
    fun itemKeysAreUnique() {
        val keys = groups.flatMap { g -> g.sections.flatMap { s -> s.items.map { it.key } } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("펀드>펀드시장규모>공모펀드 순자산총액", groups[0].sections[0].items[0].key)
    }
}
