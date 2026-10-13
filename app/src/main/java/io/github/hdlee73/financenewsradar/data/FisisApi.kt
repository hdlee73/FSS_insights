package io.github.hdlee73.financenewsradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

/** FISIS 금융권역. [code]는 FISIS 금융권역코드(A 은행, F 증권사, G 자산운용사, H 생명보험, I 손해보험, E 저축은행, C 카드사). */
data class FisisSector(val code: String, val label: String)

/** 화면에 보여 주는 권역 순서. */
val FISIS_SECTORS: List<FisisSector> = listOf(
    FisisSector("F", "금융투자(증권사)"),
    FisisSector("G", "금융투자(자산운용사)"),
    FisisSector("A", "은행"),
    FisisSector("H", "생명보험"),
    FisisSector("I", "손해보험"),
    FisisSector("E", "저축은행"),
    FisisSector("C", "카드")
)

data class FisisCompany(val code: String, val name: String)

/** 한 시점(YYYYMM)의 값. 대표이사처럼 글자 값은 [text]에 담긴다. */
data class FisisPoint(val month: String, val value: Double?, val text: String?)

/** 서버가 찾아 준 지표 하나. [group]은 fin(재무) / ratio(재무비율) / fund(펀드) / info(일반현황). */
data class FisisMetric(
    val key: String,
    val label: String,
    val group: String,
    val basis: String,
    val unit: String,
    val term: String,
    val column: String,
    val source: String,
    val points: List<FisisPoint>
) {
    fun at(month: String?): FisisPoint? = if (month == null) points.lastOrNull() else points.firstOrNull { it.month == month }
}

data class FisisProfile(
    val name: String,
    val term: String,
    val basisOptions: List<String>,
    val metrics: List<FisisMetric>,
    /** OpenDART 연동 결과 안내(연동 전이면 빈 글자). */
    val dart: String = ""
) {
    fun group(group: String, basis: String? = null): List<FisisMetric> =
        metrics.filter { it.group == group && (basis == null || it.basis == basis || it.basis.isEmpty()) }

    fun metric(key: String, basis: String? = null): FisisMetric? = metrics.firstOrNull { it.key == key && (basis == null || it.basis == basis || it.basis.isEmpty()) }
}

/** 총자산 순위용 값. */
data class FisisAsset(val value: Double, val month: String, val unit: String)
data class FisisRankingPage(val assets: Map<String, FisisAsset>, val next: Int?, val ranked: Boolean)

internal fun parseFisisCompanies(body: String): List<FisisCompany> {
    val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
        .map { FisisCompany(it.optString("code"), it.optString("name")) }
        .filter { it.code.isNotBlank() && it.name.isNotBlank() }
        .sortedBy { it.name }
}

internal fun parseFisisProfile(body: String): FisisProfile {
    val root = JSONObject(body)
    val options = root.optJSONArray("basisOptions")
    val array = root.optJSONArray("metrics")
    val metrics = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }.map { m ->
        val pts = m.optJSONArray("points")
        FisisMetric(
            key = m.optString("key"), label = m.optString("label"), group = m.optString("group"), basis = m.optString("basis"),
            unit = m.optString("unit"), term = m.optString("term"), column = m.optString("column"), source = m.optString("source"),
            points = (0 until (pts?.length() ?: 0)).map { pts!!.getJSONObject(it) }.map { p ->
                FisisPoint(
                    p.optString("month"),
                    if (p.isNull("value") || !p.has("value")) null else p.optDouble("value").takeIf { d -> d.isFinite() },
                    if (p.has("text")) p.optString("text").takeIf { t -> t.isNotBlank() } else null
                )
            }
        )
    }
    return FisisProfile(
        root.optString("name"), root.optString("term"),
        (0 until (options?.length() ?: 0)).map { options!!.getString(it) },
        metrics,
        root.optString("dart")
    )
}

internal fun parseFisisRanking(body: String): FisisRankingPage {
    val root = JSONObject(body)
    val assets = root.optJSONObject("assets")
    val map = mutableMapOf<String, FisisAsset>()
    if (assets != null) {
        for (k in assets.keys()) {
            val o = assets.getJSONObject(k)
            map[k] = FisisAsset(o.optDouble("value"), o.optString("month"), o.optString("unit"))
        }
    }
    return FisisRankingPage(map, if (root.isNull("next") || !root.has("next")) null else root.optInt("next"), root.optBoolean("ranked"))
}

/** "202509" → "2025.09". */
internal fun fisisMonthLabel(month: String): String =
    if (Regex("\\d{6}").matches(month)) month.substring(0, 4) + "." + month.substring(4) else month

internal fun fisisTermLabel(term: String): String = when (term) { "Q" -> "분기"; "H" -> "반기"; "Y" -> "연간"; else -> "" }

/** 단위(백만원·억원 등)를 원으로 바꾸는 배수. 모르는 단위는 null. */
private fun unitMultiplier(unit: String): Double? = when {
    unit.contains("조") -> 1e12
    unit.contains("억") -> 1e8
    unit.contains("백만") -> 1e6
    unit.contains("천원") || unit == "천" -> 1e3
    unit.trim() == "원" -> 1.0
    else -> null
}

/** 순위 정렬용: 값을 원 단위로 환산(단위를 모르면 원래 값). */
internal fun fisisWon(value: Double, unit: String): Double = value * (unitMultiplier(unit) ?: 1.0)

/** 금액은 "12조 3,456억원"처럼, 비율(%)은 소수 둘째 자리까지. 단위를 모르면 원래 값과 단위를 그대로 보여 준다. */
internal fun fisisValueText(value: Double, unit: String): String {
    if (unit.contains("%")) return String.format(java.util.Locale.US, "%,.2f", value) + "%"
    val mult = unitMultiplier(unit) ?: return String.format(java.util.Locale.US, "%,.2f", value).trimEnd('0').trimEnd('.') + unit
    val won = value * mult
    val eok = Math.round(abs(won) / 1e8)
    val sign = if (won < 0) "-" else ""
    return when {
        eok >= 10000 -> {
            val jo = eok / 10000
            val rest = eok % 10000
            sign + (if (rest == 0L) "${jo}조원" else String.format(java.util.Locale.US, "%d조 %,d억원", jo, rest))
        }
        eok > 0 -> sign + String.format(java.util.Locale.US, "%,d억원", eok)
        won == 0.0 -> "0원"
        else -> sign + String.format(java.util.Locale.US, "%,d만원", Math.round(abs(won) / 1e4))
    }
}

/** 서버(/fisis)로 FISIS Open API를 조회한다. 인증키는 서버에만 있다. */
class FisisApi {
    val isConfigured: Boolean get() = NewsProxy.isConfigured

    private fun get(path: String): String {
        val c = URI(NewsProxy.url.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 60_000
        c.setRequestProperty("X-App-Token", NewsProxy.token)
        try {
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val text = c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            error(message.ifBlank { "서버 오류 ($code)" })
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun companies(sector: String): List<FisisCompany> = withContext(Dispatchers.IO) {
        parseFisisCompanies(get("/fisis/companies?sector=${enc(sector)}"))
    }

    suspend fun ranking(sector: String, offset: Int): FisisRankingPage = withContext(Dispatchers.IO) {
        parseFisisRanking(get("/fisis/ranking?sector=${enc(sector)}&offset=$offset"))
    }

    suspend fun profile(sector: String, companyCode: String): FisisProfile = withContext(Dispatchers.IO) {
        parseFisisProfile(get("/fisis/profile?sector=${enc(sector)}&finance=${enc(companyCode)}"))
    }
}

/** 재무비율 지표 키별 간단한 산정방법(감독 규정 기준의 개략 설명). */
val FISIS_RATIO_METHODS: Map<String, String> = mapOf(
    "ncr" to "(영업용순자본 − 총위험액) ÷ 필요유지자기자본 × 100. 100% 이상 유지해야 합니다(회사 유형별로 세부 정의가 다릅니다).",
    "lev" to "총자산(고객 예탁분 등 일부 조정) ÷ 자기자본. 증권사는 1,100% 이내가 감독 기준입니다.",
    "liq" to "유동자산 ÷ 유동부채 × 100. 단기 지급 능력을 보는 지표입니다.",
    "eqr" to "자기자본 ÷ 총자산 × 100.",
    "bis" to "자기자본(총자본) ÷ 위험가중자산 × 100. 은행은 최소 8% 이상(총자본 기준) 유지해야 합니다.",
    "npl" to "고정이하여신(3개월 이상 연체 등) ÷ 총여신 × 100.",
    "delinq" to "연체 채권(1개월 이상) ÷ 총 채권(대출) × 100.",
    "lcr" to "고유동성자산 ÷ 향후 30일간 순현금유출 × 100. 100% 이상 유지해야 합니다.",
    "adj" to "조정자기자본 ÷ 조정총자산 × 100. 카드사는 8% 이상 유지해야 합니다.",
    "solv" to "가용자본 ÷ 요구자본 × 100(K-ICS). 100% 이상이 기준이고 150% 이상을 권고합니다.",
    "per13" to "보험계약이 13회차(1년 뒤)에도 유지되는 비율.",
    "loss" to "발생손해액 ÷ 경과보험료 × 100.",
    "opex" to "사업비 ÷ 보험료 × 100."
)
