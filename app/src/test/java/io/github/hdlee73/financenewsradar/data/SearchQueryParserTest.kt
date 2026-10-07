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
    fun `collects dash prefixed words and phrases as excluded terms`() {
        val plan = SearchQueryParser.parse("금감원 증권사 -연예 -\"인사 발령\"")

        assertEquals(listOf("금감원 증권사"), plan.providerQueries)
        assertEquals(listOf("연예", "인사 발령"), plan.excludedTerms)
        assertEquals(listOf("금감원", "증권사"), plan.terms)
    }

    @Test
    fun `keeps hyphens inside words and exclusion works with OR`() {
        assertEquals(listOf("e-mail"), SearchQueryParser.parse("e-mail").providerQueries)
        val plan = SearchQueryParser.parse("금감원 AND (증권사 OR 운용사) -광고")
        assertEquals(listOf("금감원 증권사", "금감원 운용사"), plan.providerQueries)
        assertEquals(listOf("광고"), plan.excludedTerms)
    }

    @Test
    fun `matches excluded terms ignoring case`() {
        assertTrue(SearchQueryParser.isExcluded("Samsung 연예 소식", listOf("연예")))
        assertTrue(SearchQueryParser.isExcluded("BREAKING news", listOf("breaking")))
        assertFalse(SearchQueryParser.isExcluded("금감원 제재", listOf("연예")))
    }

    @Test
    fun `rejects a query made only of excluded terms`() {
        assertThrows(IllegalArgumentException::class.java) { SearchQueryParser.parse("-연예") }
    }

    @Test
    fun `reports malformed expression`() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchQueryParser.parse("금감원 AND (증권사 OR)")
        }
    }
}
