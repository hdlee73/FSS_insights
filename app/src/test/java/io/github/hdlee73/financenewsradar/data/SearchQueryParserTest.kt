package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryParserTest {
    @Test
    fun `expands parenthesized OR into independent AND queries`() {
        val plan = SearchQueryParser.parse("금감원 AND (증권사 OR 자산운용사)")

        assertEquals(listOf("금감원 증권사", "금감원 자산운용사"), plan.providerQueries)
        assertTrue(plan.usesBooleanOperators)
    }

    @Test
    fun `treats spaces as AND and preserves quoted phrases`() {
        val plan = SearchQueryParser.parse("\"내부 통제\" 금융사고")

        assertEquals(listOf("\"내부 통제\" 금융사고"), plan.providerQueries)
        assertFalse(plan.usesBooleanOperators)
    }

    @Test
    fun `accepts symbolic Boolean operators`() {
        assertEquals(
            listOf("금감원 증권사", "금감원 운용사"),
            SearchQueryParser.parse("금감원 & (증권사 | 운용사)").providerQueries
        )
    }

    @Test
    fun `reports malformed expression`() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchQueryParser.parse("금감원 AND (증권사 OR)")
        }
    }
}
