package io.github.hdlee73.financenewsradar.accounting.data

import io.github.hdlee73.financenewsradar.accounting.domain.Actor
import io.github.hdlee73.financenewsradar.accounting.domain.ActorKind
import io.github.hdlee73.financenewsradar.accounting.domain.CompanyKind
import io.github.hdlee73.financenewsradar.accounting.domain.Motive
import io.github.hdlee73.financenewsradar.accounting.domain.SimSettings
import io.github.hdlee73.financenewsradar.accounting.domain.StockSummary
import io.github.hdlee73.financenewsradar.accounting.domain.VType
import io.github.hdlee73.financenewsradar.accounting.domain.ViolationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimCodecTest {
    @Test fun roundTripKeepsEverything() {
        val s = SimSettings(
            kind = CompanyKind.KOSPI, assetEok = "1,000", salesEok = "500",
            rows = listOf(ViolationRow(1, VType.B, VType.B.defaultBasis, "12.5", mapOf(1 to Motive.GROSS_NEGLIGENCE, 4 to Motive.INTENT), "매출 과대계상"), ViolationRow(3, title = "재고 과대")),
            actors = listOf(Actor(1, ActorKind.COMPANY, "회사", setOf("a", "b"), upFreePct = 25), Actor(4, ActorKind.OFFICER, "甲", benefitEok = "2")),
            years = 3, capmEok = "10"
        )
        val stock = StockSummary("005930", "삼성전자", "유가증권", 70000, "20251230", 1_000_000_000, 245, "2025-01-01", "2025-12-31", 245_000_000_000)
        val sim = SavedSim("id1", "테스트", "2026-11-07 10:00", s, "삼성전자", stock)
        val back = SimCodec.decodeList(SimCodec.encodeList(listOf(sim)))
        assertEquals(listOf(sim), back)
    }

    @Test fun badItemsAreSkippedAndUnknownEnumsDefault() {
        val txt = """{"format":"fss-accounting-simulation","items":[{"name":"x","settings":{"kind":"NOPE","rows":[{"type":"ZZ","title":"t"}]}}, 5]}"""
        val list = SimCodec.decodeList(txt)
        assertEquals(1, list.size)
        assertEquals(SimSettings().kind, list[0].settings.kind)
        assertEquals("t", list[0].settings.rows[0].title)
        assertTrue(list[0].stock == null)
    }

    @Test fun legacyFileIsMigratedToActors() {
        val txt = """{"items":[{"name":"old","settings":{"auditorOn":true,"auditorMotive":"INTENT","officerBenefitEok":"3","companyReasons":["c_comp"],
            "rows":[{"id":1,"type":"A","amountEok":"10","motive":"NEGLIGENCE"}]}}]}"""
        val s = SimCodec.decodeList(txt).single().settings
        assertEquals(4, s.actors.size)
        assertEquals("3", s.actors.first { it.kind == ActorKind.OFFICER }.benefitEok)
        assertEquals(setOf("c_comp"), s.actors.first { it.kind == ActorKind.COMPANY }.reasons)
        val a = s.rows.single().assign
        assertEquals(Motive.NEGLIGENCE, a[1]); assertEquals(Motive.INTENT, a[2])
    }
}
