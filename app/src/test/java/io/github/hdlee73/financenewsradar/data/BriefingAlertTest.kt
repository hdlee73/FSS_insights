package io.github.hdlee73.financenewsradar.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingAlertTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 9, h, m, 30, 0, seoul)

    @Test fun laterTodayWaitsUntilThenTime() {
        assertEquals(1L * 3600 + 29 * 60 + 30, BriefingAlert.initialDelay(at(7, 0), 8, 30).seconds)
    }

    @Test fun alreadyPassedWaitsUntilTomorrow() {
        val delay = BriefingAlert.initialDelay(at(9, 0), 8, 30)
        assertEquals(23L * 3600 + 30 * 60 - 30, delay.seconds)
    }

    @Test fun exactlyNowMovesToTomorrow() {
        val now = ZonedDateTime.of(2026, 10, 9, 8, 30, 0, 0, seoul)
        assertEquals(24L * 3600, BriefingAlert.initialDelay(now, 8, 30).seconds)
    }

    @Test fun formatsMinutesOfDay() {
        assertEquals("08:05", BriefingAlert.formatTime(8 * 60 + 5))
        assertEquals("23:59", BriefingAlert.formatTime(23 * 60 + 59))
    }
}
