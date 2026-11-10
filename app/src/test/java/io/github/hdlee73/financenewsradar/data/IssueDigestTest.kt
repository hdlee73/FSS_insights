package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NewsArticle
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueDigestTest {
    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private fun a(t: String) = NewsArticle(t, "https://x/" + t.length, "연합뉴스", now.minusSeconds(3600), "", listOf("금융감독원"))

    @Test
    fun wordsAndSchedules() {
        val arts = listOf(a("금감원장, 오늘 증권사 CEO 간담회 참석"), a("금융위원장 가계부채 점검회의 주재"), a("가상자산 거래소 점검 착수"), a("가상자산 규제 논의 본격화"))
        val d = IssueDigestBuilder.build(arts, emptyList(), now)!!
        assertEquals("가상자산", d.words.first { it.text == "가상자산" }.text)
        assertEquals(listOf("금융감독원장", "금융위원장").toSet(), d.schedules.map { it.who }.toSet())
        assertTrue(d.total == 4)
    }

    @Test
    fun emptyIsNull() = assertEquals(null, IssueDigestBuilder.build(emptyList(), emptyList(), now))
}
