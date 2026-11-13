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
 * 익명 사용 통계. 하루 한 번 서버에 (랜덤 설치ID, 앱 버전, 날짜, 메뉴별 이용 횟수·이용 시간대(시)·오류 횟수, 접속 환경 대분류)만 보낸다.
 * 같은 날 앱을 업데이트해 처음 여는 경우에도 새 버전 신호를 한 번 더 보낸다(버전 분포가 하루 늦게 바뀌는 것을 막는다).
 * 사용자별 하루 방문 횟수(앱을 앞으로 가져올 때마다 1회)와 사용 시간(화면이 보이는 동안 1분 단위, 백그라운드 제외)의 '그날 누적값'도 함께 보낸다.
 * 서버는 최댓값으로 받으므로 같은 값을 다시 보내도 중복 집계되지 않으며, 서버가 live 응답을 준 뒤에만 추가 전송한다(옛 서버는 추가 필드를 무시).
 * 이름·계정·기기 정보·검색어·IP는 보내지 않으며, 앱 정보 화면에서 끌 수 있다. 서버가 없거나 오프라인이면 조용히 건너뛴다.
 */
object UsageStats {
    private const val PREFS = "usage_stats"
    private const val KEY_ID = "install_id"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PENDING = "pending"
    private const val KEY_LAST_PING = "last_ping_day"
    private const val KEY_LAST_VERSION = "last_ping_version"
    private const val KEY_STAT = "stat" // 날짜별 방문(v)·사용 시간(s, 초) 누적
    private const val KEY_ACKED = "acked" // 서버가 받았다고 확인한 날짜별 누적값 서명
    private const val KEY_LIVE = "live" // 서버가 방문·시간 수집을 지원한다고 알려 준 적이 있는지
    private const val KEEP_DAYS = 14L
    private const val MAX_GAP_MS = 90_000L // 절전·지연으로 한 번에 크게 늘어나는 것을 막는다
    const val TICK_MS = 60_000L
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var lastTick = 0L
    @Volatile private var skipNextVisit = false

    /** 오류 기록용으로 애플리케이션 컨텍스트를 보관한다(MainActivity 시작 때 한 번). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /** 끄면 아직 보내지 않은 기록을 지운다. */
    fun setEnabled(context: Context, enabled: Boolean) = synchronized(lock) {
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) editor.remove(KEY_PENDING).remove(KEY_STAT).remove(KEY_ACKED) else lastTick = System.currentTimeMillis()
        editor.apply()
    }

    /** 앱이 앞으로 올 때마다(MainActivity.onStart). 화면 회전 등 설정 변경에 의한 재시작은 방문으로 세지 않는다. */
    fun onForeground(context: Context) {
        runCatching {
            if (!isEnabled(context)) return
            synchronized(lock) {
                val p = prefs(context)
                val stat = UsageLog.parse(p.getString(KEY_STAT, null))
                if (skipNextVisit) skipNextVisit = false else UsageLog.addVisit(stat, LocalDate.now().toString())
                UsageLog.prune(stat, LocalDate.now().minusDays(KEEP_DAYS).toString())
                p.edit().putString(KEY_STAT, stat.toString()).apply()
                lastTick = System.currentTimeMillis()
            }
        }
    }

    /** 앞에 보이는 동안 1분마다 호출해 사용 시간을 쌓는다. */
    fun tick(context: Context) {
        runCatching {
            if (!isEnabled(context)) return
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val gap = minOf(now - lastTick, MAX_GAP_MS)
                lastTick = now
                if (gap <= 0) return
                val p = prefs(context)
                val stat = UsageLog.parse(p.getString(KEY_STAT, null))
                UsageLog.addSeconds(stat, LocalDate.now().toString(), (gap / 1000).toInt())
                p.edit().putString(KEY_STAT, stat.toString()).apply()
            }
        }
    }

    /** 앱이 뒤로 갈 때(MainActivity.onStop). 마지막 구간을 더하고, 설정 변경이면 다음 시작을 방문으로 세지 않는다. */
    fun onBackground(context: Context, configChange: Boolean) {
        tick(context)
        if (configChange) skipNextVisit = true
    }

    /** 메뉴(화면)를 열 때마다 호출한다. 기기에 날짜별 횟수만 쌓는다. */
    fun recordMenu(context: Context, label: String) {
        if (!isEnabled(context)) return
        synchronized(lock) {
            val p = prefs(context)
            val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
            val day = LocalDate.now().toString()
            UsageLog.add(pending, day, label)
            UsageLog.add(pending, day, "~h:" + java.time.LocalTime.now().hour) // 이용 시간대(기기 시계 기준 '시')
            p.edit().putString(KEY_PENDING, pending.toString()).apply()
        }
    }

    /** 오류·실패 한 건(이름만, 주소·내용은 남기지 않는다). 예: "api:dart". 같은 날 같은 이름은 횟수만 쌓는다. */
    fun recordError(name: String) {
        val context = appContext ?: return
        runCatching {
            if (!isEnabled(context)) return
            val clean = name.filter { it.isLetterOrDigit() || it in ":/._-" }.take(40)
            if (clean.isEmpty()) return
            synchronized(lock) {
                val p = prefs(context)
                val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
                val day = LocalDate.now().toString()
                val menus = pending.optJSONObject(day) ?: JSONObject().also { pending.put(day, it) }
                val key = "~e:$clean"
                menus.put(key, minOf(1000, menus.optInt(key, 0) + 1))
                p.edit().putString(KEY_PENDING, pending.toString()).apply()
            }
        }
    }

    /** 접속 환경 대분류(기기 종류·안드로이드 버전·화면 크기). 모델·일련번호·IP는 보내지 않는다. */
    private fun envInfo(context: Context): JSONObject {
        val cfg = context.resources.configuration
        val width = cfg.screenWidthDp
        return JSONObject()
            .put("dev", if (cfg.smallestScreenWidthDp >= 600) "태블릿" else "휴대폰")
            .put("br", "앱")
            .put("os", "Android " + android.os.Build.VERSION.RELEASE.substringBefore('.'))
            .put("sc", when { width < 360 -> "360 미만"; width < 412 -> "360~411"; width < 600 -> "412~599"; else -> "600 이상" })
            .put("inst", "앱")
    }

    /** 오늘 아직 신호를 보내지 않았거나 지난 날짜의 기록이 남아 있으면 서버에 보낸다. 실패하면 다음에 다시 시도한다. */
    suspend fun sendIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            if (!isEnabled(context) || !NewsProxy.isConfigured) return@runCatching
            val p = prefs(context)
            val today = LocalDate.now().toString()
            var sigs = emptyMap<String, String>()
            val (body, sentDays) = synchronized(lock) {
                val id = p.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_ID, it).apply() }
                val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
                UsageLog.prune(pending, LocalDate.now().minusDays(14).toString())
                val stat = UsageLog.parse(p.getString(KEY_STAT, null))
                val acked = UsageLog.parse(p.getString(KEY_ACKED, null))
                val result = UsageLog.payload(
                    id, BuildConfig.VERSION_NAME, pending, today, env = envInfo(context),
                    includeToday = p.getString(KEY_LAST_PING, null) != today || p.getString(KEY_LAST_VERSION, null) != BuildConfig.VERSION_NAME,
                    stat = stat, acked = acked, live = p.getBoolean(KEY_LIVE, false),
                )
                sigs = result.second.mapNotNull { d -> stat.optJSONObject(d)?.let { d to UsageLog.sig(it) } }.toMap()
                result
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
                if (c.responseCode == 200) {
                    val live = runCatching { JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optBoolean("live") }.getOrDefault(false)
                    synchronized(lock) {
                        val pending = UsageLog.parse(p.getString(KEY_PENDING, null))
                        sentDays.filter { it < today }.forEach { pending.remove(it) }
                        val editor = p.edit().putString(KEY_PENDING, pending.toString()).putString(KEY_LAST_PING, today).putString(KEY_LAST_VERSION, BuildConfig.VERSION_NAME)
                        if (live) {
                            val acked = UsageLog.parse(p.getString(KEY_ACKED, null))
                            sigs.forEach { (d, sg) -> acked.put(d, sg) }
                            UsageLog.prune(acked, LocalDate.now().minusDays(KEEP_DAYS).toString())
                            editor.putBoolean(KEY_LIVE, true).putString(KEY_ACKED, acked.toString())
                        }
                        editor.apply()
                    }
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

    fun addVisit(stat: JSONObject, day: String) {
        val x = stat.optJSONObject(day) ?: JSONObject().also { stat.put(day, it) }
        x.put("v", minOf(2000, x.optInt("v", 0) + 1))
    }

    fun addSeconds(stat: JSONObject, day: String, seconds: Int) {
        if (seconds <= 0) return
        val x = stat.optJSONObject(day) ?: JSONObject().also { stat.put(day, it) }
        x.put("s", minOf(86400, x.optInt("s", 0) + seconds))
    }

    /** 방문·시간 누적값의 서명. 서버가 받았다고 확인한 값과 다를 때만 다시 보낸다. */
    fun sig(x: JSONObject): String = "${x.optInt("v", 0)}:${x.optInt("s", 0)}"

    fun prune(pending: JSONObject, oldestDay: String) {
        pending.keys().asSequence().toList().filter { it < oldestDay }.forEach { pending.remove(it) }
    }

    /**
     * 보낼 본문과 그 안에 담은 날짜들. 지난 날짜는 메뉴 횟수까지, 오늘은 "오늘 접속했다"는 신호만 담는다
     * (오늘 횟수는 날이 바뀐 뒤 한 번만 보내 중복 집계를 피한다). 보낼 것이 없으면 body 는 null.
     */
    fun payload(
        installId: String, version: String, pending: JSONObject, today: String, includeToday: Boolean, env: JSONObject? = null,
        stat: JSONObject = JSONObject(), acked: JSONObject = JSONObject(), live: Boolean = false,
    ): Pair<JSONObject?, List<String>> {
        val objs = sortedMapOf<String, JSONObject>()
        pending.keys().asSequence().toList().filter { it < today }.forEach { day ->
            objs[day] = JSONObject().put("d", day).put("m", pending.getJSONObject(day))
        }
        if (includeToday) objs[today] = JSONObject().put("d", today).put("m", JSONObject())
        if (live) {
            // 서버가 아직 받지 못한 방문·시간 누적값이 있는 날짜(지난 날짜 포함)
            stat.keys().asSequence().toList().sorted().takeLast(10).forEach { day ->
                val x = stat.optJSONObject(day) ?: return@forEach
                if (sig(x) != acked.optString(day)) objs.getOrPut(day) { JSONObject().put("d", day).put("m", JSONObject()) }
            }
        }
        // 서버는 한 번에 10일까지만 받는다. 최근 날짜를 우선하고 나머지는 다음에 보낸다.
        val keep = objs.keys.toList().takeLast(10)
        val days = JSONArray()
        val sent = mutableListOf<String>()
        keep.forEach { day ->
            val o = objs.getValue(day)
            stat.optJSONObject(day)?.let { o.put("vis", it.optInt("v", 0)).put("sec", it.optInt("s", 0)) }
            days.put(o)
            sent += day
        }
        if (days.length() == 0) return null to emptyList()
        val body = JSONObject().put("id", installId).put("v", version).put("days", days)
        if (env != null) body.put("env", env)
        return body to sent
    }
}
