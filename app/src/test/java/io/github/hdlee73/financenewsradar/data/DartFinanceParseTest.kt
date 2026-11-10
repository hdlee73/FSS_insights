package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DartFinanceParseTest {
    private fun block(basis: String, assets: Double) =
        """"basis":"$basis","balance":[{"label":"2025.12","assets":$assets,"liab":1.0,"equity":2.0}],"income":[{"label":"2025년 연간","sub":"","revenue":10.0,"op":2.0,"net":1.0}],"trend":[],"eps":{"2025":100.0}"""

    @Test fun 연결과_별도를_함께_읽는다() {
        val f = parseDartFinance("""{${block("연결", 200.0)},"annualYear":2025,"note":"","separate":{${block("별도", 100.0)}}}""")
        assertEquals("연결", f.basis)
        assertEquals(200.0, f.balance[0].assets!!, 0.0)
        assertNotNull(f.separate)
        assertEquals("별도", f.separate!!.basis)
        assertEquals(100.0, f.separate!!.balance[0].assets!!, 0.0)
        assertEquals(100.0, f.separate!!.eps[2025]!!, 0.0)
    }

    @Test fun 별도_정보가_없으면_null() {
        val f = parseDartFinance("""{${block("별도", 100.0)},"annualYear":2025,"note":"","separate":null}""")
        assertEquals("별도", f.basis)
        assertNull(f.separate)
        assertNull(parseDartFinance("""{${block("연결", 1.0)},"annualYear":2025,"note":""}""").separate)
    }
}
