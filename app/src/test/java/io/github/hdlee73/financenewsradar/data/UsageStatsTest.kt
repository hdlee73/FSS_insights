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
    fun payloadCarriesEnvAndSpecialKeys() {
        val p = JSONObject()
        UsageLog.add(p, "2026-11-03", "~h:9")
        UsageLog.add(p, "2026-11-03", "~e:api:dart")
        val env = JSONObject().put("dev", "휴대폰").put("os", "Android 14")
        val (body, _) = UsageLog.payload("id-1", "2.7.0", p, "2026-11-04", includeToday = false, env = env)
        assertEquals("휴대폰", body!!.getJSONObject("env").getString("dev"))
        val m = body.getJSONArray("days").getJSONObject(0).getJSONObject("m")
        assertEquals(1, m.getInt("~h:9"))
        assertEquals(1, m.getInt("~e:api:dart"))
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

    @Test
    fun visitsAndSecondsAccumulatePerDay() {
        val stat = JSONObject()
        UsageLog.addVisit(stat, "2026-11-13")
        UsageLog.addVisit(stat, "2026-11-13")
        UsageLog.addSeconds(stat, "2026-11-13", 60)
        UsageLog.addSeconds(stat, "2026-11-13", 30)
        UsageLog.addSeconds(stat, "2026-11-13", 0)
        UsageLog.addSeconds(stat, "2026-11-13", 999_999)
        val x = stat.getJSONObject("2026-11-13")
        assertEquals(2, x.getInt("v"))
        assertEquals(86400, x.getInt("s")) // 하루 상한
        assertEquals("2:86400", UsageLog.sig(x))
    }

    @Test
    fun payloadAttachesActivityButOldServerGetsNoExtraDays() {
        val pending = JSONObject()
        val stat = JSONObject()
        UsageLog.addVisit(stat, "2026-11-04")
        UsageLog.addSeconds(stat, "2026-11-04", 120)
        UsageLog.addVisit(stat, "2026-11-05")
        // 서버가 아직 방문·시간 수집을 지원하는지 모르면(live=false) 새 날짜 객체를 만들지 않고, 오늘 신호에만 얹는다.
        val (body, days) = UsageLog.payload("id-1", "2.8.0", pending, "2026-11-05", includeToday = true, stat = stat, live = false)
        assertEquals(listOf("2026-11-05"), days)
        val o = body!!.getJSONArray("days").getJSONObject(0)
        assertEquals(1, o.getInt("vis"))
        assertEquals(0, o.getInt("sec"))
    }

    @Test
    fun liveServerGetsOnlyUnackedDaysAndNothingWhenAllAcked() {
        val stat = JSONObject()
        UsageLog.addVisit(stat, "2026-11-04")
        UsageLog.addSeconds(stat, "2026-11-04", 120)
        UsageLog.addVisit(stat, "2026-11-05")
        val acked = JSONObject().put("2026-11-04", UsageLog.sig(stat.getJSONObject("2026-11-04")))
        val (body, days) = UsageLog.payload("id-1", "2.8.0", JSONObject(), "2026-11-05", includeToday = false, stat = stat, acked = acked, live = true)
        assertEquals(listOf("2026-11-05"), days)
        assertEquals(1, body!!.getJSONArray("days").getJSONObject(0).getInt("vis"))
        acked.put("2026-11-05", UsageLog.sig(stat.getJSONObject("2026-11-05")))
        val (none, noDays) = UsageLog.payload("id-1", "2.8.0", JSONObject(), "2026-11-05", includeToday = false, stat = stat, acked = acked, live = true)
        assertNull(none)
        assertTrue(noDays.isEmpty())
    }

    @Test
    fun payloadNeverExceedsTenDaysAndKeepsNewest() {
        val pending = JSONObject()
        for (d in 1..12) UsageLog.add(pending, "2026-10-%02d".format(d), "뉴스 검색")
        val (body, days) = UsageLog.payload("id-1", "2.8.0", pending, "2026-11-01", includeToday = true)
        assertEquals(10, days.size)
        assertEquals("2026-11-01", days.last())
        assertEquals(10, body!!.getJSONArray("days").length())
    }
}
