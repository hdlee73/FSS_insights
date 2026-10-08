package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordAlertLogicTest {
    private fun hit(link: String) = AlertHit("제목 $link", link, "출처")

    @Test fun firstRunSeedsWithoutAlerting() {
        val (fresh, seen) = KeywordAlertLogic.diff(listOf(hit("a"), hit("b")), null)
        assertTrue(fresh.isEmpty())
        assertEquals(listOf("a", "b"), seen)
    }

    @Test fun onlyUnseenLinksAlertAndAreRecorded() {
        val (fresh, seen) = KeywordAlertLogic.diff(listOf(hit("a"), hit("b"), hit("b")), listOf("a"))
        assertEquals(listOf("b"), fresh.map { it.link })
        assertEquals(listOf("a", "b"), seen)
    }

    @Test fun sameItemIsNotAlertedTwice() {
        val first = KeywordAlertLogic.diff(listOf(hit("a")), listOf("x"))
        val second = KeywordAlertLogic.diff(listOf(hit("a")), first.second)
        assertTrue(second.first.isEmpty())
    }

    @Test fun failedFetchChangesNothing() {
        val (fresh, seen) = KeywordAlertLogic.diff(null, null)
        assertTrue(fresh.isEmpty())
        assertNull(seen)
    }

    @Test fun titleMatchHonoursOrAndExclusion() {
        assertTrue(KeywordAlertLogic.titleMatches("사모펀드 점검 결과", "사모펀드"))
        assertTrue(KeywordAlertLogic.titleMatches("공매도 제도 개선", "사모펀드 OR 공매도"))
        assertFalse(KeywordAlertLogic.titleMatches("사모펀드 홍보", "사모펀드 -홍보"))
        assertFalse(KeywordAlertLogic.titleMatches("무관한 제목", "사모펀드"))
    }
}
