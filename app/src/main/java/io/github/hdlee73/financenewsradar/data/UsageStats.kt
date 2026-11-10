package io.github.hdlee73.financenewsradar.data

import android.content.Context
import io.github.hdlee73.financenewsradar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/**
 * 익명 사용 통계. 하루 한 번 서버에 (랜덤 설치ID, 앱 버전, 날짜, 메뉴별 이용 횟수)만 보낸다.
 * 같은 날 앱을 업데이트해 처음 여는 경우에도 새 버전 신호를 한 번 더 보낸다(버전 분포가 하루 늦게 바뀌는 것을 막는다).
 * 이름·계정·기기 정보·검색어·IP는 보내지 않으며, 앱 정보 화면에서 끌 수 있다. 서버가 없거나 오프라인이면 조용히 건너뛴다.
 */
object UsageStats {
    private const val PREFS = "usage_stats"
    private const val KEY_ID = "install_id"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PENDING = "pending"
    private const val KEY_LAST_PING = "last_ping_day"
    private const val KEY_LAST_VERSION = "last_ping_version"
    private val lock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /** 끄면 아직 보내지 않은 기록을 지운다. */
    fun setEnabled(context: Context, enabled: Boolean) = synchronized(lock) {
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) editor.remove(KEY_PENDING)
        editor.apply()
    }

    /** 메뉴(화면)를 열 때마다 호출한다. 기기에 날짜별 횟수만 쌓는다. */
    fun recordMenu(context: Context, label: String) {
        if (!isEnabled(context)) return
        synchronized(lock) {
            val p = prefs(context)
            val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
            UsageLog.add(pending, LocalDate.now().toString(), label)
            p.edit().putString(KEY_PENDING, pending.toString()).apply()
        }
    }

    /** 오늘 아직 신호를 보내지 않았거나 지난 날짜의 기록이 남아 있으면 서버에 보낸다. 실패하면 다음에 다시 시도한다. */
    suspend fun sendIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            if (!isEnabled(context) || !NewsProxy.isConfigured) return@runCatching
            val p = prefs(context)
            val today = LocalDate.now().toString()
            val (body, sentDays) = synchronized(lock) {
                val id = p.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_ID, it).apply() }
                val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
                UsageLog.prune(pending, LocalDate.now().minusDays(14).toString())
                UsageLog.payload(id, BuildConfig.VERSION_NAME, pending, today, includeToday = p.getString(KEY_LAST_PING, null) != today || p.getString(KEY_LAST_VERSION, null) != BuildConfig.VERSION_NAME)
            }
            if (body == null) return@runCatching
            val c = URI(NewsProxy.url.trimEnd('/') + "/usage/ping").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("X-App-Token", NewsProxy.token)
            c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (c.responseCode == 200) synchronized(lock) {
                    val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
                    sentDays.filter { it < today }.forEach { pending.remove(it) }
                    p.edit().putString(KEY_PENDING, pending.toString()).putString(KEY_LAST_PING, today).putString(KEY_LAST_VERSION, BuildConfig.VERSION_NAME).apply()
                }
            } finally {
                c.disconnect()
            }
        }
        Unit
    }
}

/** 날짜별·메뉴별 횟수를 쌓고 서버로 보낼 본문을 만드는 순수 함수 모음. */
internal object UsageLog {
    fun parse(raw: String?): JSONObject = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())

    fun add(pending: JSONObject, day: String, label: String) {
        val menus = pending.optJSONObject(day) ?: JSONObject().also { pending.put(day, it) }
        menus.put(label, menus.optInt(label, 0) + 1)
    }

    fun prune(pending: JSONObject, oldestDay: String) {
        pending.keys().asSequence().toList().filter { it < oldestDay }.forEach { pending.remove(it) }
    }

    /**
     * 보낼 본문과 그 안에 담은 날짜들. 지난 날짜는 메뉴 횟수까지, 오늘은 "오늘 접속했다"는 신호만 담는다
     * (오늘 횟수는 날이 바뀐 뒤 한 번만 보내 중복 집계를 피한다). 보낼 것이 없으면 body 는 null.
     */
    fun payload(installId: String, version: String, pending: JSONObject, today: String, includeToday: Boolean): Pair<JSONObject?, List<String>> {
        val days = JSONArray()
        val sent = mutableListOf<String>()
        pending.keys().asSequence().toList().sorted().filter { it < today }.forEach { day ->
            days.put(JSONObject().put("d", day).put("m", pending.getJSONObject(day)))
            sent += day
        }
        if (includeToday) {
            days.put(JSONObject().put("d", today).put("m", JSONObject()))
            sent += today
        }
        if (days.length() == 0) return null to emptyList()
        return JSONObject().put("id", installId).put("v", version).put("days", days) to sent
    }
}
