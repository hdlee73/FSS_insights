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
    val group: String get() = STAT_CATALOG.firstOrNull { it.id == id }?.group ?: "기타"
}

/** 고를 수 있는 국내 금융 통계 하나. id는 서버(worker.js)의 STAT_SPECS와 같아야 한다. */
data class StatDef(val id: String, val name: String, val group: String)

/** 통계 선택 화면에 보여 주는 후보와 순서. 화면에서도 이 순서대로 묶어 보여 준다. */
val STAT_CATALOG: List<StatDef> = listOf(
    StatDef("base-rate", "한국은행 기준금리", "금리·채권"),
    StatDef("call-1d", "콜금리(1일)", "금리·채권"),
    StatDef("cd-91", "CD 91일", "금리·채권"),
    StatDef("cp-91", "CP 91일", "금리·채권"),
    StatDef("ktb-1y", "국고채 1년", "금리·채권"),
    StatDef("ktb-2y", "국고채 2년", "금리·채권"),
    StatDef("ktb-3y", "국고채 3년", "금리·채권"),
    StatDef("ktb-5y", "국고채 5년", "금리·채권"),
    StatDef("ktb-10y", "국고채 10년", "금리·채권"),
    StatDef("ktb-20y", "국고채 20년", "금리·채권"),
    StatDef("ktb-30y", "국고채 30년", "금리·채권"),
    StatDef("corp-aa", "회사채 3년 AA-", "금리·채권"),
    StatDef("corp-bbb", "회사채 3년 BBB-", "금리·채권"),
    StatDef("loan-rate", "예금은행 대출금리", "금리·채권"),
    StatDef("mortgage-rate", "예금은행 주택담보대출금리", "금리·채권"),
    StatDef("household-credit", "가계신용 잔액", "가계·은행 건전성"),
    StatDef("household-credit-loan", "가계대출 잔액(분기)", "가계·은행 건전성"),
    StatDef("bank-household-loan", "은행 가계대출 잔액", "가계·은행 건전성"),
    StatDef("deposit-household-loan", "예금취급기관 가계대출", "가계·은행 건전성"),
    StatDef("bank-mortgage", "은행 주택관련대출", "가계·은행 건전성"),
    StatDef("bank-delinquency", "은행 가계대출 연체율", "가계·은행 건전성"),
    StatDef("bank-delinquency-corp", "은행 기업대출 연체율", "가계·은행 건전성"),
    StatDef("fx-reserves", "외환보유액", "대외")
)

/** 처음 설치했을 때 시장동향 탭에 보이는 통계. */
val DEFAULT_MARKET_STATS: List<String> =
    listOf("base-rate", "ktb-3y", "ktb-10y", "cd-91", "corp-aa", "household-credit", "bank-household-loan", "bank-delinquency", "loan-rate", "fx-reserves")

/** 처음 설치했을 때 오늘의 브리핑에 보이는 통계. */
val DEFAULT_BRIEFING_STATS: List<String> = listOf("ktb-3y", "corp-aa")

/** 사용자가 고른 통계: 시장동향 탭에 보일 것과, 그중 오늘의 브리핑에도 보일 것. */
data class StatSelection(val market: Set<String>, val briefing: Set<String>) {
    val all: List<String> get() = STAT_CATALOG.map { it.id }.filter { it in market || it in briefing }
}

/** 서버 /stats 응답: 값이 있는 [items]와, 서버가 값을 못 찾았다고 알려 준 통계 id [missing]. */
data class StatsResult(val items: List<StatItem>, val missing: Set<String> = emptySet())

internal fun parseStatsResult(body: String): StatsResult {
    val root = JSONObject(body)
    val missing = root.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    return StatsResult(parseStats(body), missing)
}

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
    suspend fun load(ids: List<String>): StatsResult = withContext(Dispatchers.IO) {
        if (!NewsProxy.isConfigured || ids.isEmpty()) return@withContext StatsResult(emptyList())
        val c = URI(NewsProxy.url.trimEnd('/') + "/stats?ids=" + ids.joinToString(",")).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 40_000
        c.setRequestProperty("X-App-Token", NewsProxy.token)
        try {
            if (c.responseCode != 200) error("통계 서버 응답 ${c.responseCode}")
            parseStatsResult(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }
}
