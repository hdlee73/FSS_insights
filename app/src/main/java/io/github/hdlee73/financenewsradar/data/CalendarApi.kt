package io.github.hdlee73.financenewsradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate

/** 금융 일정 한 건. 하루짜리면 [endDate]가 [date]와 같다. [confirmed]가 false면 공식 일정 확인 전이다. */
data class CalendarEvent(
    val date: LocalDate,
    val endDate: LocalDate,
    val title: String,
    val category: String,
    val note: String = "",
    val url: String = "",
    val confirmed: Boolean = true
) {
    fun covers(day: LocalDate): Boolean = !day.isBefore(date) && !day.isAfter(endDate)
}

object CalendarParser {
    fun parse(text: String): List<CalendarEvent> {
        val array = JSONObject(text).optJSONArray("events") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val start = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null
            val end = o.optString("endDate").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeIf { !it.isBefore(start) } ?: start
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CalendarEvent(
                start, end, title, o.optString("category", "기타").ifBlank { "기타" },
                o.optString("note"), o.optString("url"), o.optBoolean("confirmed", true)
            )
        }.sortedWith(compareBy({ it.date }, { it.title }))
    }
}

/** 서버 /calendar 에서 금융 일정을 읽는다. */
class CalendarApi {
    val isConfigured: Boolean get() = NewsProxy.isConfigured

    suspend fun load(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val c = URI(NewsProxy.url.trimEnd('/') + "/calendar").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.setRequestProperty("X-App-Token", NewsProxy.token)
        try {
            val code = c.responseCode
            if (code !in 200..299) error("서버 오류 ($code)")
            CalendarParser.parse(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }
}
