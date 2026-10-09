package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GreetingTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(month: Int, day: Int, hour: Int) = ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, seoul)

    @Test
    fun sameDayAndTimeBucketGivesSameGreeting() {
        assertEquals(Greeting.pick(at(10, 9, 9)), Greeting.pick(at(10, 9, 11)))
    }

    @Test
    fun greetingsVaryAcrossDays() {
        val texts = (1..28).map { Greeting.pick(at(10, it, 9)) }.toSet()
        assertTrue("문구가 날마다 거의 같다: $texts", texts.size >= 5)
    }

    @Test
    fun poolFollowsTimeWeekdayAndWeather() {
        // 2026-10-09는 금요일.
        val pool = Greeting.candidates(at(10, 9, 13))
        assertTrue(pool.any { it.contains("점심") })
        assertTrue(pool.any { it.contains("금요일") })
        val rain = Weather("🌧️", "비", 14, 10, 17, 80)
        assertTrue(Greeting.candidates(at(10, 9, 13), rain).any { it.contains("비") })
        assertNotEquals(Greeting.candidates(at(10, 9, 13)), Greeting.candidates(at(10, 9, 20)))
    }

    @Test
    fun everyHourHasAPool() {
        (0..23).forEach { assertTrue(Greeting.candidates(at(10, 8, it)).isNotEmpty()) }
    }
}
