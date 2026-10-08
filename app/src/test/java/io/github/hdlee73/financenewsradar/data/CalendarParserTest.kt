package io.github.hdlee73.financenewsradar.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarParserTest {
    @Test
    fun parsesSortsAndSkipsBadRows() {
        val text = """{"events":[
            {"date":"2026-11-26","title":"B","category":"금통위"},
            {"date":"2026-10-27","endDate":"2026-10-28","title":"A","confirmed":false},
            {"date":"bad","title":"X"},
            {"date":"2026-10-01","title":""}
        ]}"""
        val events = CalendarParser.parse(text)
        assertEquals(listOf("A", "B"), events.map { it.title })
        assertEquals("기타", events[0].category)
        assertFalse(events[0].confirmed)
        assertTrue(events[1].confirmed)
    }

    @Test
    fun endBeforeStartFallsBackToSingleDay() {
        val e = CalendarParser.parse("""{"events":[{"date":"2026-10-10","endDate":"2026-10-01","title":"A"}]}""").single()
        assertEquals(LocalDate.of(2026, 10, 10), e.endDate)
    }

    @Test
    fun coversRange() {
        val e = CalendarEvent(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28), "A", "해외")
        assertTrue(e.covers(LocalDate.of(2026, 10, 28)))
        assertFalse(e.covers(LocalDate.of(2026, 10, 29)))
    }
}
