package io.github.hdlee73.financenewsradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** FISIS 금융권역. [code]는 FISIS 금융권역코드(A 은행, F 증권사, G 자산운용사, H 생명보험, I 손해보험, E 저축은행, C 카드사). */
data class FisisSector(val code: String, val label: String)

/** 화면에 보여 주는 권역 순서. */
val FISIS_SECTORS: List<FisisSector> = listOf(
    FisisSector("F", "증권사"),
    FisisSector("G", "자산운용사"),
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
    val metrics: List<FisisMetric>
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
        metrics
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
private fun unitMultiplier(unit: String): Double? {
    val u = unit.replace(" ", "")
    return when {
        u.contains("조") -> 1e12
        u.contains("억") -> 1e8
        u.contains("백만") -> 1e6
        u.contains("천만") -> 1e7
        u.contains("만원") || u == "만" -> 1e4
        u.contains("천원") || u == "천" -> 1e3
        u.contains("원") -> 1.0
        else -> null
    }
}

/** 순위 정렬용: 값을 원 단위로 환산(단위를 모르면 원래 값). */
internal fun fisisWon(value: Double, unit: String): Double = value * (unitMultiplier(unit) ?: 1.0)

/** 금액은 항상 억원으로(10억원 이상은 소수점 없이 "603,926억원"), 비율은 소수 둘째 자리까지 "12.34%"로 보여 준다. 단위를 모르면 원래 값과 단위를 그대로 보여 준다. */
internal fun fisisValueText(value: Double, unit: String): String {
    val us = java.util.Locale.US
    if (unit.contains("%")) return String.format(us, "%,.2f", value) + "%"
    val mult = unitMultiplier(unit) ?: return String.format(us, "%,.2f", value).trimEnd('0').trimEnd('.') + unit
    val eok = value * mult / 1e8
    // 10억원 이상은 소수점 없이, 그보다 작은 금액(소규모 회사)은 0으로 사라지지 않게 소수 둘째 자리까지 보여 준다.
    return if (kotlin.math.abs(eok) >= 10 || eok == 0.0) String.format(us, "%,d억원", Math.round(eok))
    else String.format(us, "%,.2f", eok).trimEnd('0').trimEnd('.') + "억원"
}

private fun homepageKey(name: String) = name.replace(Regex("\\s|\\(주\\)|주식회사|㈜"), "")

/** 알려진 금융회사의 공식 홈페이지(회사 이름에 키가 들어 있으면 그 주소). 먼저 나온 항목이 우선한다. */
private val FISIS_HOMEPAGES: List<Pair<String, String>> = listOf(
    // 은행
    "국민은행" to "https://www.kbstar.com", "신한은행" to "https://www.shinhan.com", "하나은행" to "https://www.kebhana.com",
    "우리은행" to "https://www.wooribank.com", "중소기업은행" to "https://www.ibk.co.kr", "농협은행" to "https://www.nhbank.com",
    "산업은행" to "https://www.kdb.co.kr", "씨티은행" to "https://www.citibank.co.kr", "SC제일은행" to "https://www.standardchartered.co.kr",
    "카카오뱅크" to "https://www.kakaobank.com", "케이뱅크" to "https://www.kbank.com", "토스뱅크" to "https://www.tossbank.com",
    "수협은행" to "https://www.suhyup-bank.com", "부산은행" to "https://www.busanbank.co.kr", "아이엠뱅크" to "https://www.imbank.co.kr",
    "iM뱅크" to "https://www.imbank.co.kr", "대구은행" to "https://www.imbank.co.kr", "경남은행" to "https://www.knbank.co.kr",
    "광주은행" to "https://www.kjbank.com", "전북은행" to "https://www.jbbank.co.kr", "제주은행" to "https://www.jejubank.co.kr",
    // 증권
    "미래에셋증권" to "https://securities.miraeasset.com", "삼성증권" to "https://www.samsungpop.com", "한국투자증권" to "https://www.koreainvestment.com",
    "NH투자증권" to "https://www.nhqv.com", "KB증권" to "https://www.kbsec.com", "신한투자증권" to "https://www.shinhansec.com",
    "하나증권" to "https://www.hanaw.com", "메리츠증권" to "https://www.imeritz.com", "키움증권" to "https://www.kiwoom.com",
    "대신증권" to "https://www.daishin.com", "유안타증권" to "https://www.yuantasecurities.co.kr", "한화투자증권" to "https://www.hanwhawm.com",
    "교보증권" to "https://www.iprovest.com", "현대차증권" to "https://www.hmsec.com", "SK증권" to "https://www.sks.co.kr",
    "유진투자증권" to "https://www.eugenefn.com", "부국증권" to "https://www.bookook.co.kr", "IBK투자증권" to "https://www.ibks.com",
    "BNK투자증권" to "https://www.bnkfn.co.kr", "카카오페이증권" to "https://www.kakaopaysec.com", "토스증권" to "https://www.tossinvest.com",
    "다올투자증권" to "https://www.daolsecurities.com",
    // 자산운용
    "삼성자산운용" to "https://www.samsungfund.com", "미래에셋자산운용" to "https://investments.miraeasset.com", "KB자산운용" to "https://www.kbam.co.kr",
    "한국투자신탁운용" to "https://www.kim.co.kr", "신한자산운용" to "https://www.shinhanfund.com", "한화자산운용" to "https://www.hanwhafund.com",
    "키움투자자산운용" to "https://www.kiwoomam.com",
    // 보험
    "삼성생명" to "https://www.samsunglife.com", "한화생명" to "https://www.hanwhalife.com", "교보생명" to "https://www.kyobo.com",
    "신한라이프" to "https://www.shinhanlife.co.kr", "NH농협생명" to "https://www.nhlife.co.kr", "농협생명" to "https://www.nhlife.co.kr",
    "흥국생명" to "https://www.heungkuklife.co.kr", "동양생명" to "https://www.myangel.co.kr", "ABL생명" to "https://www.abllife.co.kr",
    "미래에셋생명" to "https://life.miraeasset.com", "삼성화재" to "https://www.samsungfire.com", "현대해상" to "https://www.hi.co.kr",
    "DB손해보험" to "https://www.idbins.com", "KB손해보험" to "https://www.kbinsure.co.kr", "메리츠화재" to "https://www.meritzfire.com",
    "한화손해보험" to "https://www.hwgeneralins.com", "롯데손해보험" to "https://www.lotteins.co.kr", "흥국화재" to "https://www.heungkukfire.co.kr",
    "농협손해보험" to "https://www.nhfire.co.kr",
    // 카드
    "신한카드" to "https://www.shinhancard.com", "삼성카드" to "https://www.samsungcard.com", "현대카드" to "https://www.hyundaicard.com",
    "국민카드" to "https://www.kbcard.com", "롯데카드" to "https://www.lottecard.co.kr", "우리카드" to "https://pc.wooricard.com",
    "하나카드" to "https://www.hanacard.co.kr", "비씨카드" to "https://www.bccard.com",
    // 저축은행
    "SBI저축은행" to "https://www.sbisb.co.kr", "OK저축은행" to "https://www.oksavingsbank.com", "웰컴저축은행" to "https://www.welcomebank.co.kr"
)

/** 회사 홈페이지 주소와, 목록에 있는 주소(true)인지 검색 주소(false)인지. */
data class FisisHomepage(val url: String, val known: Boolean)

fun fisisHomepage(name: String): FisisHomepage {
    val key = homepageKey(name)
    FISIS_HOMEPAGES.firstOrNull { key.contains(it.first, ignoreCase = true) }?.let { return FisisHomepage(it.second, true) }
    return FisisHomepage("https://www.google.com/search?q=" + URLEncoder.encode("$name 공식 홈페이지", "UTF-8"), false)
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
