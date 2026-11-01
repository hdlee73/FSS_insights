package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** 회사 검색 결과·관심기업 한 곳. [stock]이 비어 있으면 비상장사다. [market]은 KS(유가증권)·KQ(코스닥)이며 모르면 빈 값. */
data class DartCorp(val corp: String, val name: String, val stock: String, val market: String = "") {
    /** 시세 조회용 심볼(예: 086790.KS). 비상장이면 null. */
    val symbol: String? get() = stock.takeIf { it.length == 6 }?.let { "$it.${market.ifBlank { "KS" }}" }
}

data class DartCompany(
    val corp: String, val name: String, val nameEn: String, val stock: String, val market: String, val marketName: String,
    val ceo: String, val corpNo: String, val bizNo: String, val address: String, val homepage: String, val ir: String,
    val phone: String, val industryCode: String, val established: String, val accountMonth: String
)

data class DartHolder(val name: String, val relation: String, val kind: String, val shares: Double?, val ratio: Double?)
data class DartHolders(val basis: String, val items: List<DartHolder>)

/** [src]는 DART 또는 KIND, [date]는 yyyyMMdd. */
data class DartFiling(val id: String, val title: String, val date: String, val by: String, val note: String, val url: String, val src: String)
data class DartFilings(val items: List<DartFiling>, val error: String? = null)

private val KEY_PARAM = Regex("crtfc_key=[^&\\s\"'<>]*", RegexOption.IGNORE_CASE)

/** 오류 문구에서 DART 인증키(crtfc_key=…) 값을 가린다. */
internal fun redactKey(text: String): String = text.replace(KEY_PARAM, "crtfc_key=***")

internal fun parseDartCorps(body: String): List<DartCorp> {
    val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
        .map { DartCorp(it.optString("corp"), it.optString("name"), it.optString("stock")) }
        .filter { it.corp.isNotBlank() && it.name.isNotBlank() }
}

internal fun parseDartCompany(body: String): DartCompany {
    val o = JSONObject(body)
    return DartCompany(
        corp = o.optString("corp"), name = o.optString("name"), nameEn = o.optString("nameEn"), stock = o.optString("stock"),
        market = o.optString("market"), marketName = o.optString("marketName"), ceo = o.optString("ceo"), corpNo = o.optString("corpNo"),
        bizNo = o.optString("bizNo"), address = o.optString("address"), homepage = o.optString("homepage"), ir = o.optString("ir"),
        phone = o.optString("phone"), industryCode = o.optString("industryCode"), established = o.optString("established"),
        accountMonth = o.optString("accountMonth")
    )
}

internal fun parseDartHolders(body: String): DartHolders {
    val o = JSONObject(body)
    val array = o.optJSONArray("items") ?: JSONArray()
    val items = (0 until array.length()).map { array.getJSONObject(it) }.map {
        DartHolder(
            name = it.optString("name"), relation = it.optString("relation"), kind = it.optString("kind"),
            shares = if (it.isNull("shares")) null else it.optDouble("shares"),
            ratio = if (it.isNull("ratio")) null else it.optDouble("ratio")
        )
    }
    return DartHolders(o.optString("basis"), items)
}

internal fun parseDartFilings(body: String): DartFilings {
    val o = JSONObject(body)
    val array = o.optJSONArray("items") ?: JSONArray()
    val items = (0 until array.length()).map { array.getJSONObject(it) }.map {
        DartFiling(it.optString("id"), it.optString("title"), it.optString("date"), it.optString("by"), it.optString("note"), it.optString("url"), it.optString("src"))
    }.filter { it.title.isNotBlank() && it.url.startsWith("https://") }
    return DartFilings(items, o.optString("error").ifBlank { null })
}

/** 서버(/dart)로 DART Open API와 KIND 공시를 조회한다. 인증키는 서버에만 있다. */
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

    /** [type]: a 정기보고서, f 감사보고서, e 기타공시(DART). */
    suspend fun filings(corp: String, type: String): DartFilings = withContext(Dispatchers.IO) { parseDartFilings(get("/dart/filings?corp=${enc(corp)}&type=$type")) }
    suspend fun kind(name: String): DartFilings = withContext(Dispatchers.IO) { parseDartFilings(get("/dart/kind?name=${enc(name)}")) }
}

/** 관심기업·최근 검색·"새 공시" 기준일을 이 기기에만 저장한다. */
class DartStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dart_watch", Context.MODE_PRIVATE)

    fun watch(): List<DartCorp> = runCatching {
        val array = JSONArray(prefs.getString(WATCH, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map { DartCorp(it.optString("corp"), it.optString("name"), it.optString("stock"), it.optString("market")) }
    }.getOrDefault(emptyList())

    fun saveWatch(items: List<DartCorp>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("corp", it.corp).put("name", it.name).put("stock", it.stock).put("market", it.market)) }
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
