package io.github.hdlee73.financenewsradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** FISIS 통계표 하나. [listNo]는 FISIS 통계표 번호(서버가 그대로 FISIS에 전달한다). */
data class FisisTable(val listNo: String, val label: String)

/** FISIS 금융권역. [code]는 FISIS 금융권역코드(통계표 번호의 두 번째 글자와 같다). */
data class FisisSector(val code: String, val label: String, val tables: List<FisisTable>)

/** 화면에 보여 주는 권역 순서: 금융투자, 은행, 보험(생명·손해). */
val FISIS_SECTORS: List<FisisSector> = listOf(
    FisisSector(
        "F", "금융투자", listOf(
            FisisTable("SF303", "재무제표 ①"), FisisTable("SF304", "재무제표 ②"),
            FisisTable("SF308", "NCR"), FisisTable("SF331", "레버리지비율")
        )
    ),
    FisisSector(
        "A", "은행", listOf(
            FisisTable("SA003", "자산"), FisisTable("SA004", "부채·자본"), FisisTable("SA021", "손익계산서"),
            FisisTable("SA014", "BIS 자기자본비율"), FisisTable("SA040", "연체율"), FisisTable("SA041", "고정이하여신비율")
        )
    ),
    FisisSector(
        "H", "생명보험", listOf(
            FisisTable("SH021", "지급여력"), FisisTable("SH150", "자산 요약"), FisisTable("SH160", "신계약"),
            FisisTable("SH025", "유지율"), FisisTable("SH114", "효율")
        )
    ),
    FisisSector(
        "I", "손해보험", listOf(
            FisisTable("SI021", "지급여력"), FisisTable("SI150", "손익 요약"), FisisTable("SI027", "원수보험료"),
            FisisTable("SI025", "유지율")
        )
    )
)

data class FisisCompany(val code: String, val name: String)

/** 한 시점(base_month: YYYYMM)의 값. 열 이름(a, b…)별로 담는다. */
data class FisisPoint(val month: String, val values: Map<String, Double?>)

data class FisisAccount(val code: String, val name: String, val points: List<FisisPoint>) {
    fun latest(column: String): FisisPoint? = points.lastOrNull { it.values[column] != null }

    /** 직전 시점 값 대비 마지막 값의 증감. */
    fun change(column: String): Double? {
        val have = points.filter { it.values[column] != null }
        if (have.size < 2) return null
        return have.last().values[column]!! - have[have.size - 2].values[column]!!
    }

    /** 비율 계정(%)인지: 이름으로 추정한다. */
    val isRatio: Boolean get() = Regex("비율|률|NCR|%").containsMatchIn(name)
}

data class FisisColumn(val id: String, val name: String)

/** 한 회사·한 통계표의 조회 결과. [term]은 서버가 실제로 쓴 주기(Q 분기, H 반기, Y 연). 비어 있으면 자료 없음. */
data class FisisData(val term: String, val name: String, val columns: List<FisisColumn>, val accounts: List<FisisAccount>) {
    val isEmpty: Boolean get() = accounts.isEmpty()
}

internal fun parseFisisCompanies(body: String): List<FisisCompany> {
    val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
        .map { FisisCompany(it.optString("code"), it.optString("name")) }
        .filter { it.code.isNotBlank() && it.name.isNotBlank() }
        .sortedBy { it.name }
}

internal fun parseFisisData(body: String): FisisData {
    val root = JSONObject(body)
    val cols = root.optJSONArray("columns")
    val columns = (0 until (cols?.length() ?: 0)).map { cols!!.getJSONObject(it) }
        .map { FisisColumn(it.optString("id"), it.optString("name")) }
    val accountArray = root.optJSONArray("accounts")
    val accounts = (0 until (accountArray?.length() ?: 0)).map { accountArray!!.getJSONObject(it) }.map { a ->
        val pts = a.optJSONArray("points")
        FisisAccount(
            a.optString("code"), a.optString("name"),
            (0 until (pts?.length() ?: 0)).map { pts!!.getJSONObject(it) }.map { p ->
                val v = p.optJSONObject("v")
                FisisPoint(p.optString("month"), columns.associate { c -> c.id to v?.takeIf { j -> !j.isNull(c.id) }?.optDouble(c.id)?.takeIf { d -> d.isFinite() } })
            }
        )
    }
    return FisisData(root.optString("term"), root.optString("name"), columns, accounts)
}

/** "202509" → "2025.09". */
internal fun fisisMonthLabel(month: String): String =
    if (Regex("\\d{6}").matches(month)) month.substring(0, 4) + "." + month.substring(4) else month

/** 값을 읽기 쉽게: 비율은 소수 둘째 자리 %, 금액은 천 단위 쉼표(FISIS 원자료 단위). */
internal fun fisisValueText(value: Double, ratio: Boolean): String =
    if (ratio) String.format(java.util.Locale.US, "%,.2f", value) + "%"
    else String.format(java.util.Locale.US, "%,.0f", value)

/** 서버(/fisis)로 FISIS Open API를 조회한다. 인증키는 서버에만 있다. */
class FisisApi {
    val isConfigured: Boolean get() = NewsProxy.isConfigured

    private fun get(path: String): String {
        val c = URI(NewsProxy.url.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 45_000
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

    suspend fun companies(sector: String): List<FisisCompany> = withContext(Dispatchers.IO) {
        parseFisisCompanies(get("/fisis/companies?sector=" + URLEncoder.encode(sector, "UTF-8")))
    }

    suspend fun data(companyCode: String, listNo: String, term: String = "Q"): FisisData = withContext(Dispatchers.IO) {
        parseFisisData(get("/fisis/data?finance=${URLEncoder.encode(companyCode, "UTF-8")}&list=$listNo&term=$term"))
    }
}
