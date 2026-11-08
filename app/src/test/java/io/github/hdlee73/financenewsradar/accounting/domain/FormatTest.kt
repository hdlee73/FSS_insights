package io.github.hdlee73.financenewsradar.accounting.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test fun won_units() {
        assertEquals("-", Format.won(null))
        assertEquals("31.6억원", Format.won(3_161_000_000))
        assertEquals("24억원", Format.won(2_400_000_000))
        assertEquals("1.5조원", Format.won(1_500_000_000_000))
        assertEquals("5,000만원", Format.won(50_000_000))
    }
    @Test fun date_and_input() {
        assertEquals("2026.10.28", Format.dateYmd("20261028"))
        assertEquals("abc", Format.dateYmd("abc"))

        assertEquals(150_000_000L, Format.eokToWon("1.5"))
        assertNull(Format.eokToWon("abc")); assertNull(Format.eokToWon("-1")); assertNull(Format.eokToWon("")); assertNull(Format.eokToWon("100000001")); assertEquals(450_000_000_000_000L, Format.eokToWon("4500000"))
    }
}
