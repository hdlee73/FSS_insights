package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** 시장동향 탭 "국내 금융 통계" 칸 하나. [previous]는 직전 관측값(기준금리는 직전 변경 전 값). */
data class StatItem(
    val id: String,
    val name: String,
    val unit: String,
    val value: Double,
    val previous: Double?,
    /** ECOS 기준 시점: 20261007(일), 202609(월), 2026Q2(분기). */
    val period: String
) {
    val change: Double? get() = previous?.let { value - it }
    val changePercent: Double? get() = previous?.takeIf { it != 0.0 }?.let { (value - it) / kotlin.math.abs(it) * 100 }
    val isRate: Boolean get() = unit == "%"
    val group: String get() = STAT_GROUPS.entries.firstOrNull { id in it.value }?.key ?: "기타"
}

/** 화면에서 묶어 보여 주는 순서와 구성. */
val STAT_GROUPS: Map<String, List<String>> = linkedMapOf(
    "금리·채권" to listOf("base-rate", "ktb-3y", "ktb-10y", "cd-91", "corp-aa", "loan-rate"),
    "가계·은행 건전성" to listOf("household-credit", "bank-household-loan", "bank-delinquency"),
    "대외" to listOf("fx-reserves")
)

/** 서버 /stats 응답에서 값이 있는 항목만 읽는다. */
internal fun parseStats(body: String): List<StatItem> {
    val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).mapNotNull {
        val o = array.getJSONObject(it)
        val value = o.optDouble("value", Double.NaN)
        if (!value.isFinite()) return@mapNotNull null
        StatItem(
            id = o.getString("id"),
            name = o.getString("name"),
            unit = o.optString("unit"),
            value = value,
            previous = o.optDouble("previous", Double.NaN).takeIf { p -> p.isFinite() },
            period = o.optString("period")
        )
    }
}

/** 기준 시점을 "10/07", "2026.09", "2026 2분기"처럼 읽기 쉽게 바꾼다. */
internal fun periodLabel(period: String): String = when {
    Regex("\\d{8}").matches(period) -> period.substring(4, 6) + "/" + period.substring(6, 8)
    Regex("\\d{6}").matches(period) -> period.substring(0, 4) + "." + period.substring(4, 6)
    Regex("\\d{4}Q[1-4]").matches(period) -> period.substring(0, 4) + " " + period.last() + "분기"
    else -> period
}

/** 서버(/stats)에서 한국은행 ECOS 통계를 받아 온다. 서버가 없거나 키가 없으면 빈 목록. */
class StatsApi(private val context: Context) {
    suspend fun load(): List<StatItem> = withContext(Dispatchers.IO) {
        if (!NewsProxy.isConfigured) return@withContext emptyList()
        val c = URI(NewsProxy.url.trimEnd('/') + "/stats").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 40_000
        c.setRequestProperty("X-App-Token", NewsProxy.token)
        try {
            if (c.responseCode != 200) error("통계 서버 응답 ${c.responseCode}")
            parseStats(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }
}
