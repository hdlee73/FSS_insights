package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * 회사 검색 결과·관심기업 한 곳. [stock]이 비어 있으면 비상장사다.
 * [cls]는 DART 법인구분: Y 유가증권, K 코스닥, N 코넥스, E 기타법인.
 */
data class DartCorp(val corp: String, val name: String, val stock: String, val cls: String = "") {
    /** 시세 조회용 심볼(예: 086790.KS). 비상장이면 null. */
    val symbol: String? get() = stock.takeIf { it.length == 6 }?.let { "$it.${if (cls == "K") "KQ" else "KS"}" }
}

/** 시장 단위 이름(기업개황의 법인구분). 모르면 상장 여부로 짐작한다. */
fun dartMarketLabel(corp: DartCorp): String = dartMarketLabel(corp.cls.ifBlank { if (corp.stock.isBlank()) "E" else "Y" })

fun dartMarketLabel(cls: String): String = when (cls) {
    "Y" -> "유가증권"
    "K" -> "코스닥"
    "N" -> "코넥스"
    else -> "기타법인"
}

data class DartCompany(
    val corp: String, val name: String, val nameEn: String, val stock: String, val cls: String,
    val ceo: String, val corpNo: String, val bizNo: String, val address: String, val homepage: String, val ir: String,
    val phone: String, val industryCode: String, val established: String, val accountMonth: String
)

/** 한 주주의 보통주·우선주 지분. 해당 주식이 없으면 null. */
data class DartShare(val shares: Double?, val ratio: Double?)
data class DartHolder(val name: String, val relation: String, val common: DartShare?, val pref: DartShare?)
data class DartHolders(val basis: String, val items: List<DartHolder>, val totalCommon: Double?, val totalPref: Double?)

/** [src]는 출처(DART), [date]는 yyyyMMdd. */
data class DartFiling(val id: String, val title: String, val date: String, val by: String, val note: String, val url: String, val src: String)
data class DartFilings(val items: List<DartFiling>, val error: String? = null)

data class DartBalanceCol(val label: String, val assets: Double?, val liab: Double?, val equity: Double?)
data class DartIncomeCol(val label: String, val sub: String, val revenue: Double?, val op: Double?, val net: Double?)
data class DartTrendPoint(val year: Int, val revenue: Double?, val op: Double?, val net: Double?, val debt: Double?)

/** 재무손익 현황(단위 억원). [eps]는 연도별 주당이익(원). */
data class DartFinance(
    val basis: String, val note: String, val annualYear: Int,
    val balance: List<DartBalanceCol>, val income: List<DartIncomeCol>, val trend: List<DartTrendPoint>, val eps: Map<Int, Double>
)

private val KEY_PARAM = Regex("crtfc_key=[^&\\s\"'<>]*", RegexOption.IGNORE_CASE)

/** 오류 문구에서 DART 인증키(crtfc_key=…) 값을 가린다. */
internal fun redactKey(text: String): String = text.replace(KEY_PARAM, "crtfc_key=***")

internal fun parseDartCorps(body: String): List<DartCorp> {
    val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
        .map { DartCorp(it.optString("corp"), it.optString("name"), it.optString("stock"), it.optString("cls")) }
        .filter { it.corp.isNotBlank() && it.name.isNotBlank() }
}

internal fun parseDartCompany(body: String): DartCompany {
    val o = JSONObject(body)
    return DartCompany(
        corp = o.optString("corp"), name = o.optString("name"), nameEn = o.optString("nameEn"), stock = o.optString("stock"),
        cls = o.optString("cls"), ceo = o.optString("ceo"), corpNo = o.optString("corpNo"),
        bizNo = o.optString("bizNo"), address = o.optString("address"), homepage = o.optString("homepage"), ir = o.optString("ir"),
        phone = o.optString("phone"), industryCode = o.optString("industryCode"), established = o.optString("established"),
        accountMonth = o.optString("accountMonth")
    )
}

private fun JSONObject.num(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.share(key: String): DartShare? = optJSONObject(key)?.let { DartShare(it.num("shares"), it.num("ratio")) }

internal fun parseDartHolders(body: String): DartHolders {
    val o = JSONObject(body)
    val array = o.optJSONArray("items") ?: JSONArray()
    val items = (0 until array.length()).map { array.getJSONObject(it) }.map {
        DartHolder(name = it.optString("name"), relation = it.optString("relation"), common = it.share("common"), pref = it.share("pref"))
    }
    return DartHolders(o.optString("basis"), items, o.num("totalCommon"), o.num("totalPref"))
}

internal fun parseDartFinance(body: String): DartFinance {
    val o = JSONObject(body)
    fun <T> list(key: String, map: (JSONObject) -> T): List<T> {
        val array = o.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { map(array.getJSONObject(it)) }
    }
    val eps = HashMap<Int, Double>()
    o.optJSONObject("eps")?.let { e -> e.keys().forEach { k -> k.toIntOrNull()?.let { y -> e.num(k)?.let { v -> eps[y] = v } } } }
    return DartFinance(
        basis = o.optString("basis"), note = o.optString("note"), annualYear = o.optInt("annualYear"),
        balance = list("balance") { DartBalanceCol(it.optString("label"), it.num("assets"), it.num("liab"), it.num("equity")) },
        income = list("income") { DartIncomeCol(it.optString("label"), it.optString("sub"), it.num("revenue"), it.num("op"), it.num("net")) },
        trend = list("trend") { DartTrendPoint(it.optInt("year"), it.num("revenue"), it.num("op"), it.num("net"), it.num("debt")) },
        eps = eps
    )
}

internal fun parseDartFilings(body: String): DartFilings {
    val o = JSONObject(body)
    val array = o.optJSONArray("items") ?: JSONArray()
    val items = (0 until array.length()).map { array.getJSONObject(it) }.map {
        DartFiling(it.optString("id"), it.optString("title"), it.optString("date"), it.optString("by"), it.optString("note"), it.optString("url"), it.optString("src"))
    }.filter { it.title.isNotBlank() && it.url.startsWith("https://") }
    return DartFilings(items, o.optString("error").ifBlank { null })
}

/** 서버(/dart)로 DART Open API를 조회한다. 인증키는 서버에만 있다. */
class DartApi {
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
        } catch (e: Exception) {
            // 연결 오류 문구에 주소가 섞여 들어와도 인증키가 화면에 나오지 않게 가린다.
            throw IllegalStateException(redactKey(e.message ?: "서버에 연결하지 못했습니다."))
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(query: String): List<DartCorp> = withContext(Dispatchers.IO) { parseDartCorps(get("/dart/search?q=${enc(query)}")) }
    suspend fun company(corp: String): DartCompany = withContext(Dispatchers.IO) { parseDartCompany(get("/dart/company?corp=${enc(corp)}")) }
    suspend fun holders(corp: String): DartHolders = withContext(Dispatchers.IO) { parseDartHolders(get("/dart/holders?corp=${enc(corp)}")) }

    suspend fun finance(corp: String): DartFinance = withContext(Dispatchers.IO) { parseDartFinance(get("/dart/finance?corp=${enc(corp)}")) }

    /** [type]: a 정기보고서, m 주요사항보고서, e 기타공시. [from]·[to]는 yyyyMMdd(최대 10년). */
    suspend fun filings(corp: String, type: String, from: String, to: String): DartFilings = withContext(Dispatchers.IO) {
        parseDartFilings(get("/dart/filings?corp=${enc(corp)}&type=$type&from=$from&to=$to"))
    }

    /** 연도별 마지막 종가(네이버 연봉). 현재 연도는 진행 중이라 마지막 거래일 종가다. 실패하면 빈 값. */
    suspend fun yearEndCloses(code: String): Map<Int, Double> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URI("https://fchart.stock.naver.com/sise.nhn?symbol=$code&timeframe=year&count=9&requestType=0").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 FSSInsights/1.0")
            try {
                val xml = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                val out = HashMap<Int, Double>()
                for (m in Regex("<item data=\"([^\"]+)\"").findAll(xml)) {
                    val v = m.groupValues[1].split("|")
                    if (v.size < 5) continue
                    val year = v[0].take(4).toIntOrNull() ?: continue
                    val close = v[4].replace(",", "").toDoubleOrNull() ?: continue
                    if (close > 0) out[year] = close
                }
                out
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyMap())
    }
}

/** 관심기업·최근 검색·"새 공시" 기준일을 이 기기에만 저장한다. */
class DartStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dart_watch", Context.MODE_PRIVATE)

    fun watch(): List<DartCorp> = runCatching {
        val array = JSONArray(prefs.getString(WATCH, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            // 예전 버전은 시장을 KS/KQ로 저장했다.
            val cls = it.optString("cls").ifBlank { when (it.optString("market")) { "KQ" -> "K"; "KS" -> "Y"; else -> "" } }
            DartCorp(it.optString("corp"), it.optString("name"), it.optString("stock"), cls)
        }
    }.getOrDefault(emptyList())

    fun saveWatch(items: List<DartCorp>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("corp", it.corp).put("name", it.name).put("stock", it.stock).put("cls", it.cls)) }
        prefs.edit().putString(WATCH, array.toString()).apply()
    }

    fun recent(): List<String> = prefs.getString(RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun saveRecent(items: List<String>) {
        prefs.edit().putString(RECENT, items.joinToString("\n")).apply()
    }

    /** 사용자가 마지막으로 확인한 공시일(yyyyMMdd). 없으면 빈 값. */
    fun seen(corp: String): String = prefs.getString(SEEN + corp, "").orEmpty()

    fun setSeen(corp: String, date: String) {
        prefs.edit().putString(SEEN + corp, date).apply()
    }

    private companion object {
        const val WATCH = "watch"
        const val RECENT = "recent"
        const val SEEN = "seen_"
    }
}
