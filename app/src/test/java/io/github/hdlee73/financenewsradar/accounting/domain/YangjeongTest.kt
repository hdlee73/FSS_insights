package io.github.hdlee73.financenewsradar.accounting.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YangjeongTest {
    private val eok = 100_000_000L
    private val scale = CompanyScale(1000 * eok, 1000 * eok, CompanyKind.KOSDAQ)

    @Test fun coefficient_followsTable() {
        assertEquals(0.4, Yangjeong.coefficient(50.0), 1e-9)
        assertEquals(0.6, Yangjeong.coefficient(100.0), 1e-9)
        assertEquals(1.0, Yangjeong.coefficient(700.0), 1e-3)
        assertEquals(1.2, Yangjeong.coefficient(1000.0), 1e-3)
        assertEquals(1.5, Yangjeong.coefficient(2000.0), 1e-3)
        assertEquals(10.0, Yangjeong.coefficient(3_000_000.0), 1e-9)
    }

    @Test fun listedSmall_usesCoefficientOne() {
        val c = CompanyScale(300 * eok, 300 * eok, CompanyKind.KOSPI)
        assertEquals(300 * eok, Yangjeong.scaleAmountWon(c, ScaleBasis.AVG))
    }

    @Test fun scaleAmount_dividesByCoefficient() {
        val w = Yangjeong.scaleAmountWon(scale, ScaleBasis.AVG)
        assertEquals(1000.0 / 1.2, w / 1e8, 0.01)
    }

    @Test fun multiple_and_grade() {
        val w = Yangjeong.scaleAmountWon(scale, ScaleBasis.AVG)
        // A유형 기준 = 규모금액의 1% → 위반금액이 그 10배면 Ⅱ(8배 이상 16배 미만)
        val items = listOf(ViolationItem(VType.A, ScaleBasis.AVG, w / 100 * 10, Motive.INTENT))
        val a = Yangjeong.assess(scale, items, null, 0)
        assertEquals(10.0, a.multiples.getValue(Motive.INTENT), 0.01)
        assertEquals("고의 Ⅱ", a.base?.label)
        assertEquals("고의 Ⅱ", a.final?.label)
    }

    @Test fun belowOneTimes_hasNoGrade() {
        val items = listOf(ViolationItem(VType.A, ScaleBasis.AVG, 1 * eok, Motive.NEGLIGENCE))
        val a = Yangjeong.assess(scale, items, null, 0)
        assertNull(a.base)
    }

    @Test fun netSteps_moveLadderAndClamp() {
        val w = Yangjeong.scaleAmountWon(scale, ScaleBasis.AVG)
        val items = listOf(ViolationItem(VType.A, ScaleBasis.AVG, w / 100 * 10, Motive.GROSS_NEGLIGENCE))
        assertEquals("중과실 Ⅰ", Yangjeong.assess(scale, items, null, 1).final?.label)
        assertEquals("중과실 가중시 최대", Yangjeong.assess(scale, items, null, 9).final?.label)
        assertEquals("중과실 감경시 최소", Yangjeong.assess(scale, items, null, -9).final?.label)
    }

    @Test fun dType_isCappedAtSix() {
        val items = listOf(ViolationItem(VType.D, ScaleBasis.AVG, 100_000 * eok, Motive.INTENT))
        val a = Yangjeong.assess(scale, items, null, 0)
        assertEquals(6.0, a.multiples.getValue(Motive.INTENT), 1e-9)
    }

    @Test fun auditorMateriality_overridesStandard() {
        val items = listOf(ViolationItem(VType.B, ScaleBasis.ASSET, 40 * eok, Motive.INTENT))
        val a = Yangjeong.assess(scale, items, 5 * eok, 0)  // B = 5억×4 = 20억 → 2배
        assertEquals(2.0, a.multiples.getValue(Motive.INTENT), 1e-9)
        assertEquals("고의 Ⅳ", a.base?.label)
    }

    @Test fun mixedMotives_conversionLimitedToOneStep() {
        val items = listOf(
            ViolationItem(VType.A, ScaleBasis.AVG, 20 * eok, Motive.INTENT),
            ViolationItem(VType.A, ScaleBasis.AVG, 100 * eok, Motive.GROSS_NEGLIGENCE)
        )
        val a = Yangjeong.assess(scale, items, 1 * eok, 0)
        assertNotNull(a.before); assertNotNull(a.after)
        assertTrue(a.converted.isNotEmpty())
        assertTrue(Yangjeong.score(a.after!!.motive, a.after!!.idx) >= Yangjeong.score(a.before!!.motive, a.before!!.idx))
        assertTrue(a.after!!.idx <= a.before!!.idx + 1 || a.after!!.motive != a.before!!.motive)
    }

    @Test fun fineRate_table() {
        assertEquals(20.0 to 500.0, FineModel.rate(2.6))
        assertEquals(10.0 to 250.0, FineModel.rate(2.0))
        assertEquals(2.0 to 50.0, FineModel.rate(1.0))
    }

    @Test fun fineScore_grossNegligenceIsAllLow() {
        val r = FineModel.score(Level.HIGH, Level.HIGH, Level.HIGH, Level.HIGH, Motive.GROSS_NEGLIGENCE)
        assertEquals(1.0, r.score, 1e-9)
        assertEquals(3.0, FineModel.score(Level.HIGH, Level.HIGH, Level.HIGH, Level.HIGH, Motive.INTENT).score, 1e-9)
    }

    @Test fun fine_capmDeduction_andRounding() {
        val i = FineModel.FineInputs("회사", 100 * eok, 10 * eok, "테스트", Motive.INTENT, emptySet(), 0, 1, false, true, emptySet(), 0, 20 * eok, 4 * eok)
        val r = FineModel.compute(i)
        assertEquals(10 * eok, r.decided)
        assertEquals(6 * eok, r.afterCapm)
        val big = FineModel.compute(i.copy(capmFineWon = 12 * eok))
        assertEquals(0L, big.afterCapm)
    }

    @Test fun fine_statutoryCap_applies() {
        val i = FineModel.FineInputs("회사", 100 * eok, 50 * eok, "테스트", Motive.INTENT, emptySet(), 0, 1, false, true, emptySet(), 0, 20 * eok, null)
        val r = FineModel.compute(i)
        assertTrue(r.capped); assertEquals(20 * eok, r.decided)
    }

    @Test fun fine_yearsAggravation_intent() {
        val i = FineModel.FineInputs("회사", 100 * eok, 10 * eok, "t", Motive.INTENT, setOf(FineAdj.UP_YEARS), 0, 3, false, true, emptySet(), 0, null, null)
        assertEquals(10 * eok + 2 * 3 * eok, FineModel.compute(i).aggravated)  // 30% × (3-1)
    }

    @Test fun capm429_bounds() {
        assertEquals(20 * eok, FineModel.capm429(null, false))
        assertEquals(10 * eok, FineModel.capm429(10 * eok, true))
        assertEquals(20 * eok, FineModel.capm429(1000 * eok, true))
        assertEquals(15 * eok, FineModel.capm429(150 * eok, true))
    }

    @Test fun simulation_endToEnd() {
        val actors = defaultActors().map { if (it.kind == ActorKind.AUDITOR) it.copy(feeEok = "1") else it }
        val s = SimSettings(
            kind = CompanyKind.KOSDAQ, assetEok = "1000", salesEok = "1000", actors = actors,
            rows = listOf(ViolationRow(1, VType.A, ScaleBasis.AVG, "100", defaultAssign(actors)))
        )
        val r = Simulation.run(s)
        assertNull(r.error)
        val by = r.actors.associateBy { it.actor.kind }
        assertNotNull(by.getValue(ActorKind.COMPANY).assessment?.final)
        assertTrue(by.getValue(ActorKind.COMPANY).actions.isNotEmpty())
        assertTrue((by.getValue(ActorKind.COMPANY).fine?.result?.afterCapm ?: 0) > 0)
        assertTrue(by.getValue(ActorKind.AUDITOR).fine != null)
        assertTrue(by.getValue(ActorKind.CPA).fine == null && by.getValue(ActorKind.CPA).actions.isNotEmpty())
    }

    @Test fun simulation_perActorAssignment() {
        // 임직원 2명: 한 명은 위반1(고의)만, 다른 한 명은 위반2(과실)만. 회사는 둘 다.
        val actors = listOf(
            Actor(1, ActorKind.COMPANY, "회사"), Actor(5, ActorKind.OFFICER, "甲"), Actor(6, ActorKind.OFFICER, "乙"), Actor(2, ActorKind.AUDITOR, "감사인")
        )
        val rows = listOf(
            ViolationRow(1, VType.A, ScaleBasis.AVG, "100", mapOf(1 to Motive.INTENT, 5 to Motive.INTENT)),
            ViolationRow(2, VType.A, ScaleBasis.AVG, "100", mapOf(1 to Motive.INTENT, 6 to Motive.NEGLIGENCE))
        )
        val r = Simulation.run(SimSettings(assetEok = "1000", salesEok = "1000", rows = rows, actors = actors))
        val by = r.actors.associateBy { it.actor.id }
        assertTrue(by.getValue(1).rowCount == 2 && by.getValue(5).rowCount == 1 && by.getValue(6).rowCount == 1)
        assertTrue(by.getValue(5).fine != null)
        assertNull(by.getValue(6).fine)                          // 과실뿐이면 과징금 대상 아님
        assertTrue(by.getValue(2).assessment == null)            // 배정 없는 감사인
        assertTrue(by.getValue(1).assessment!!.multiples.getValue(Motive.INTENT) > by.getValue(5).assessment!!.multiples.getValue(Motive.INTENT))
    }

    @Test fun simulation_validation() {
        assertTrue(Simulation.run(SimSettings()).error != null)
        assertTrue(Simulation.run(SimSettings(rows = listOf(ViolationRow(1, amountEok = "10")))).error != null)
    }

    @Test fun simulation_negligenceOnly_hasNoFine() {
        val actors = listOf(Actor(1, ActorKind.COMPANY, "회사"))
        val s = SimSettings(assetEok = "1000", salesEok = "1000", actors = actors, rows = listOf(ViolationRow(1, VType.A, ScaleBasis.AVG, "100", mapOf(1 to Motive.NEGLIGENCE))))
        val r = Simulation.run(s)
        assertTrue(r.actors.all { it.fine == null })
    }
}
