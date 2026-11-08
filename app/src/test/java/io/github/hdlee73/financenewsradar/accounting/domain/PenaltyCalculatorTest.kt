package io.github.hdlee73.financenewsradar.accounting.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenaltyCalculatorTest {
    private val eok = 100_000_000L

    @Test fun intent_company_cap_is_20_percent_and_officer_10_percent_of_that() {
        val r = PenaltyCalculator.limits(SimInput(120 * eok, Motive.INTENT, auditFeeWon = 3 * eok))
        assertEquals(24 * eok, r[0].upperWon)       // 120억 × 20%
        assertEquals(24 * eok / 10, r[1].upperWon)  // 회사 상한의 10%
        assertEquals(15 * eok, r[2].upperWon)       // 보수 3억 × 5배
    }

    @Test fun negligence_gets_no_externalaudit_act_fine() {
        val r = PenaltyCalculator.limits(SimInput(120 * eok, Motive.NEGLIGENCE, auditFeeWon = 3 * eok))
        assertFalse(r[0].applicable); assertNull(r[0].upperWon)
        assertFalse(r[1].applicable); assertFalse(r[2].applicable)
    }

    @Test fun auditor_cap_needs_fee() {
        val r = PenaltyCalculator.limits(SimInput(10 * eok, Motive.GROSS_NEGLIGENCE))
        assertNull(r[2].upperWon)
    }

    @Test fun capital_market_clamped_between_10_and_20_eok() {
        fun cap(avg: Long?, traded: Boolean = true) =
            PenaltyCalculator.limits(SimInput(eok, Motive.INTENT, avgDailyTradingWon = avg, sharesTraded = traded))[3].upperWon
        assertEquals(10 * eok, cap(50 * eok))      // 10% = 5억 → 하한 10억
        assertEquals(15 * eok, cap(150 * eok))     // 15억
        assertEquals(20 * eok, cap(1000 * eok))    // 100억 → 상한 20억
        assertEquals(20 * eok, cap(null, traded = false))
        assertNull(cap(null))
    }

    @Test fun large_amount_does_not_overflow() {
        val r = PenaltyCalculator.limits(SimInput(1_000_000 * eok, Motive.INTENT))
        assertTrue(r[0].upperWon!! > 0)
    }
}
