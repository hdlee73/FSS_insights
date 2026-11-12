package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeFeedTest {
    @Test
    fun parsesNoticesAndSkipsBlankTitles() {
        val raw = """{"notices":[{"id":3,"title":" 점검 ","body":"새벽","level":"urgent"},{"id":4,"title":"","body":"x","level":"normal"},{"id":5,"title":"안내","body":"","level":"normal"}]}"""
        val list = NoticeFeed.parse(raw)
        assertEquals(listOf(3, 5), list.map { it.id })
        assertEquals("점검", list[0].title)
        assertTrue(list[0].urgent)
    }

    @Test
    fun badJsonGivesEmptyList() {
        assertTrue(NoticeFeed.parse("<html>").isEmpty())
        assertTrue(NoticeFeed.parse(null).isEmpty())
    }

    @Test
    fun hiddenOnlyCountsForToday() {
        assertEquals(setOf(1, 2), NoticeFeed.hiddenIds("2026-11-12:1,2", "2026-11-12"))
        assertTrue(NoticeFeed.hiddenIds("2026-11-11:1,2", "2026-11-12").isEmpty())
        assertTrue(NoticeFeed.hiddenIds(null, "2026-11-12").isEmpty())
    }
}
