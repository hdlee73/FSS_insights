package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NewsProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsQueryPlannerTest {
    @Test
    fun `google home search always keeps the exact watch keyword`() {
        val queries = NewsQueryPlanner.homeQueries("증권사", NewsProviderType.GOOGLE_RSS)

        assertEquals("증권사", queries.first())
        assertTrue(queries.size > 1)
    }

    @Test
    fun `naver search does not send undocumented boolean query syntax`() {
        assertEquals(
            listOf("증권사"),
            NewsQueryPlanner.homeQueries("증권사", NewsProviderType.NAVER)
        )
    }

    @Test
    fun `combined search without naver key keeps google discovery query`() {
        val queries = NewsQueryPlanner.homeQueries(
            "증권사",
            NewsProviderType.COMBINED,
            naverReady = false
        )

        assertEquals("증권사", queries.first())
        assertTrue(queries.size > 1)
    }
}
