package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate

/** 관리자가 서버(/admin/notices)에서 쓴 공지 한 건. */
data class Notice(val id: Int, val title: String, val body: String, val urgent: Boolean)

/** 서버의 /notices 에서 지금 게시 중인 공지를 받고, 이용자가 '숨김'한 공지를 하루 동안 기억한다. */
object NoticeFeed {
    private const val PREFS = "notices"
    private const val KEY_HIDDEN = "hidden"

    /** 서버 응답을 공지 목록으로 바꾼다. 형식이 틀리면 빈 목록. */
    internal fun parse(raw: String?): List<Notice> = runCatching {
        val arr = JSONObject(raw ?: return emptyList()).optJSONArray("notices") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            if (title.isEmpty()) null else Notice(o.optInt("id"), title, o.optString("body").trim(), o.optString("level") == "urgent")
        }
    }.getOrDefault(emptyList())

    /** 숨김 기록은 "날짜:id,id" 꼴. 날짜가 오늘이 아니면 비운다(다음 날 다시 보인다). */
    internal fun hiddenIds(stored: String?, today: String): Set<Int> {
        val (day, ids) = (stored ?: "").split(":", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        if (day != today) return emptySet()
        return ids.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun hidden(context: Context): Set<Int> =
        hiddenIds(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HIDDEN, null), LocalDate.now().toString())

    fun hide(context: Context, ids: Collection<Int>) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = hiddenIds(p.getString(KEY_HIDDEN, null), LocalDate.now().toString()) + ids
        p.edit().putString(KEY_HIDDEN, "${LocalDate.now()}:${all.joinToString(",")}").apply()
    }

    /** 게시 중인 공지. 서버가 없거나 오프라인이면 null(이전에 받은 것을 그대로 둔다). */
    suspend fun fetch(): List<Notice>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!NewsProxy.isConfigured) return@runCatching null
            val c = URI(NewsProxy.url.trimEnd('/') + "/notices").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("X-App-Token", NewsProxy.token)
            try {
                if (c.responseCode == 200) parse(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
