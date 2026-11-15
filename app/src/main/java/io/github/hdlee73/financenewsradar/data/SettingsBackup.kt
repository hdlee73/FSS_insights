package io.github.hdlee73.financenewsradar.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 즐겨찾기·관심기업·키워드·보관함 등 기기에만 저장된 설정을 JSON 파일로 내보내고 다시 가져온다.
 * 기기에 묶인 값(네이버 키 암호문·사용 통계 식별값·읽음 표시·업데이트/공지 상태)은 담지 않는다.
 */
object SettingsBackup {
    private const val KIND = "fss-insights-android-backup"
    private val PREF_FILES = listOf("news_radar_settings", "market_watchlist", "dart_watch", "law_settings", "menu_folds")
    private val SKIP = Regex("^(naver_.*|disclaimer_accepted|last_seen_version|links_migrated.*|seen_.*|.*migrated.*|sox_removed)$")
    private const val SIM_FILE = "simulations.json"
    private const val MAX_BYTES = 5_000_000

    class Result(val ok: Boolean, val message: String)

    fun export(context: Context): String {
        val app = context.applicationContext
        val prefs = JSONObject()
        var count = 0
        for (name in PREF_FILES) {
            val obj = JSONObject()
            for ((k, v) in app.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (SKIP.matches(k) || v == null) continue
                val e = JSONObject()
                when (v) {
                    is String -> e.put("t", "s").put("v", v)
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                    else -> continue
                }
                obj.put(k, e)
                count++
            }
            if (obj.length() > 0) prefs.put(name, obj)
        }
        val root = JSONObject().put("kind", KIND).put("exportedAt", java.time.Instant.now().toString()).put("prefs", prefs)
        runCatching { File(app.filesDir, SIM_FILE).takeIf { it.isFile && it.length() < MAX_BYTES }?.readText() }.getOrNull()
            ?.let { root.put("simulations", it); count++ }
        root.put("count", count)
        return root.toString(1)
    }

    fun suggestedName(): String = "FSS_Insights_백업_${java.time.LocalDate.now().toString().replace("-", "")}.json"

    /** 목록(문자열 집합)은 합치고 나머지는 백업 값으로 바꾼다. 형식이 맞지 않으면 아무것도 바꾸지 않는다. */
    fun import(context: Context, text: String): Result {
        val app = context.applicationContext
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Result(false, "백업 파일(JSON)이 아닙니다.")
        if (root.optString("kind") != KIND) return Result(false, "FSS Insights 안드로이드 백업 파일이 아닙니다.")
        val prefs = root.optJSONObject("prefs") ?: return Result(false, "가져올 항목이 없습니다.")
        var n = 0
        runCatching {
            for (name in PREF_FILES) {
                val obj = prefs.optJSONObject(name) ?: continue
                val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                val ed = sp.edit()
                for (k in obj.keys()) {
                    if (SKIP.matches(k)) continue
                    val e = obj.optJSONObject(k) ?: continue
                    when (e.optString("t")) {
                        "s" -> ed.putString(k, e.getString("v"))
                        "b" -> ed.putBoolean(k, e.getBoolean("v"))
                        "i" -> ed.putInt(k, e.getInt("v"))
                        "l" -> ed.putLong(k, e.getLong("v"))
                        "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                        "ss" -> {
                            val arr = e.getJSONArray("v")
                            val incoming = (0 until arr.length()).map { arr.getString(it) }
                            val cur = runCatching { sp.getStringSet(k, null) }.getOrNull().orEmpty()
                            ed.putStringSet(k, cur + incoming)
                        }
                        else -> continue
                    }
                    n++
                }
                ed.commit()
            }
            val sim = root.optString("simulations")
            val valid = sim.isNotBlank() && sim.length < MAX_BYTES &&
                (runCatching { JSONObject(sim) }.isSuccess || runCatching { JSONArray(sim) }.isSuccess)
            if (valid) {
                val f = File(app.filesDir, SIM_FILE)
                if (!f.exists() || f.length() == 0L) { f.writeText(sim); n++ }  // 기존 시뮬레이션은 덮어쓰지 않는다
            }
        }.onFailure { return Result(false, "가져오지 못했습니다. 파일이 손상되었을 수 있습니다.") }
        return if (n == 0) Result(false, "가져올 항목이 없습니다.") else Result(true, "${n}개 항목을 가져왔습니다. 앱을 다시 열면 모두 반영됩니다.")
    }
}
