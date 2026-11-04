package io.github.hdlee73.financenewsradar.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsTest {
    @Test
    fun countsMenusPerDay() {
        val p = JSONObject()
        UsageLog.add(p, "2026-11-03", "뉴스 검색")
        UsageLog.add(p, "2026-11-03", "뉴스 검색")
        UsageLog.add(p, "2026-11-03", "앱 정보")
        assertEquals(2, p.getJSONObject("2026-11-03").getInt("뉴스 검색"))
        assertEquals(1, p.getJSONObject("2026-11-03").getInt("앱 정보"))
    }

    @Test
    fun payloadSendsPastDaysWithCountsAndTodayAsPresenceOnly() {
        val p = JSONObject()
        UsageLog.add(p, "2026-11-03", "뉴스 검색")
        UsageLog.add(p, "2026-11-04", "뉴스 검색")
        val (body, days) = UsageLog.payload("id-1", "2.2.0", p, "2026-11-04", includeToday = true)
        assertEquals(listOf("2026-11-03", "2026-11-04"), days)
        val arr = body!!.getJSONArray("days")
        assertEquals(1, arr.getJSONObject(0).getJSONObject("m").getInt("뉴스 검색"))
        assertEquals(0, arr.getJSONObject(1).getJSONObject("m").length())
        assertTrue(!body.has("name") && body.getString("id") == "id-1")
    }

    @Test
    fun nothingToSendWhenAlreadyPingedToday() {
        val p = JSONObject()
        UsageLog.add(p, "2026-11-04", "뉴스 검색")
        assertNull(UsageLog.payload("id-1", "2.2.0", p, "2026-11-04", includeToday = false).first)
    }

    @Test
    fun pruneDropsOldDays() {
        val p = JSONObject()
        UsageLog.add(p, "2026-10-01", "a")
        UsageLog.add(p, "2026-11-03", "a")
        UsageLog.prune(p, "2026-10-21")
        assertTrue(!p.has("2026-10-01") && p.has("2026-11-03"))
    }
}
