package io.github.hdlee73.financenewsradar.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class FreeSisDirectTest {
    private fun dates() = JSONObject().put(
        "dsLatestDate",
        JSONArray().put(JSONObject().put("TMPV1", "RD").put("TMPV2", "20261126"))
            .put(JSONObject().put("TMPV1", "RQ").put("TMPV2", "20260930"))
            .put(JSONObject().put("TMPV1", "RY").put("TMPV2", "20251231"))
    )

    private fun rows(n: Int, v: Int) = JSONArray().apply {
        put(JSONObject().put("TMPV1", "합계").put("TMPV14", 999999))
        for (i in 1..n) put(JSONObject().put("TMPV1", "운용사$i").put("TMPV14", v + i).put("TMPV13", if (i == 1) 1 else 0))
    }

    @Test
    fun buildsSameShapeAsServerFile() {
        val now = ZonedDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.ofHours(9))
        val text = FreeSisDirect.build({ ep, _ ->
            if (ep == "getSrvData.do") dates() else JSONObject().put("ds1", rows(120, 100))
        }, now)
        val data = parseFundAum(text)
        assertEquals("20260930", data.asOf.quarter)
        assertEquals("2026-11-28T10:00:00+09:00", data.generatedAt)
        assertEquals(120, data.companies.size) // 합계 줄은 제외
        val c = data.find("운용사1")!!
        assertEquals(100L, c.pub[0]) // 101 - 기관전용 사모 1
        assertEquals(3, c.pri.size)
    }

    @Test
    fun blockedNetworkFails() {
        try {
            FreeSisDirect.build({ _, _ -> null })
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("FreeSIS"))
        }
    }

    @Test
    fun tooFewRowsFails() {
        try {
            FreeSisDirect.build({ ep, _ -> if (ep == "getSrvData.do") dates() else JSONObject().put("ds1", rows(5, 1)) })
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("너무 적"))
        }
    }

    @Test
    fun staleDetection() {
        val now = Instant.parse("2026-11-28T01:00:00Z")
        assertFalse(FundAumRepository.isStale("2026-11-28T07:30:00+09:00", now))
        assertTrue(FundAumRepository.isStale("2026-11-26T07:30:00+09:00", now))
        assertTrue(FundAumRepository.isStale("", now))
    }
}
