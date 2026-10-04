package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NewsArticle
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordTrendTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 10, 4)

    private fun article(daysAgo: Long, n: Int = 0) = NewsArticle(
        title = "기사 $daysAgo-$n", link = "https://x/$daysAgo/$n", source = "s",
        publishedAt = today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant(), summary = ""
    )

    @Test
    fun countsPerDayOldestFirst() {
        val articles = List(3) { article(0, it) } + List(2) { article(1, it) } + article(6) + article(7)
        val trend = KeywordTrend.compute("금융", articles, today, zone)
        assertEquals(listOf(1, 0, 0, 0, 0, 2, 3), trend.daily)
    }

    @Test
    fun surgeNeedsAtLeastFiveAndDoubleTheAverage() {
        val surge = KeywordTrend.compute("a", List(8) { article(0, it) } + List(6) { article(1, it) }, today, zone)
        assertTrue(surge.isSurge)
        val few = KeywordTrend.compute("b", List(4) { article(0, it) }, today, zone)
        assertFalse(few.isSurge)
        val steady = KeywordTrend.compute("c", (0L..6L).flatMap { d -> List(6) { article(d, it) } }, today, zone)
        assertFalse(steady.isSurge)
    }
}
