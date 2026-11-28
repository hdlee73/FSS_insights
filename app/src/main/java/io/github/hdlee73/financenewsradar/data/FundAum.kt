package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** 한 회사의 펀드 순자산총액(억원). 값 세 개는 [FundAumDataset.asOf]의 최근일자·최직근 분기말·최직근 연말 순서. */
data class FundAumCompany(val name: String, val pub: List<Long>, val pri: List<Long>) {
    fun total(i: Int): Long = pub[i] + pri[i]
}

/** 조회 시점(YYYYMMDD) 세 개. */
data class FundAumDates(val recent: String, val quarter: String, val year: String) {
    fun at(i: Int): String = listOf(recent, quarter, year)[i]
}

data class FundAumDataset(val generatedAt: String, val asOf: FundAumDates, val companies: List<FundAumCompany>) {
    /**
     * 금융회사 주요 현황의 회사 이름으로 찾는다. 증권사(sector "F")는 이름이 똑같을 때만(끝의 '증권'을 떼어 같은 계열
     * 자산운용사 수치를 잘못 붙이지 않도록), 운용사는 이름이 같거나 끝의 "자산운용" 등을 뗀 이름이 증권사 아닌 한 곳으로 맞을 때만.
     */
    fun find(company: String, sector: String = "G"): FundAumCompany? {
        val k = FundAumNames.key(company)
        if (k.isEmpty()) return null
        val exact = companies.firstOrNull { FundAumNames.key(it.name) == k }
        if (exact != null || sector == "F" || k.endsWith("증권")) return exact
        val s = FundAumNames.stem(company)
        if (s.length < 2) return null
        val hits = companies.filter { !FundAumNames.key(it.name).endsWith("증권") && FundAumNames.stem(it.name) == s }
        return hits.singleOrNull()
    }
}

object FundAumNames {
    private val NOISE = Regex("주식회사|유한회사|\\(주\\)|㈜|\\(유\\)|\\s|[.,·]")
    private val TAIL = Regex("(자산운용|투자운용|투자자문|자산관리|운용|증권)$")

    fun key(name: String): String = name.replace(NOISE, "").lowercase()
    fun stem(name: String): String = key(name).replace(TAIL, "")
}

internal fun parseFundAum(text: String): FundAumDataset {
    val o = JSONObject(text)
    val d = o.getJSONObject("asOf")
    val arr = o.getJSONArray("companies")
    val list = ArrayList<FundAumCompany>(arr.length())
    for (i in 0 until arr.length()) {
        val c = arr.getJSONObject(i)
        fun nums(key: String): List<Long> {
            val a = c.getJSONArray(key)
            return List(3) { j -> if (j < a.length()) a.optLong(j) else 0L }
        }
        list += FundAumCompany(c.getString("n"), nums("pub"), nums("pri"))
    }
    return FundAumDataset(o.optString("generatedAt"), FundAumDates(d.getString("recent"), d.getString("quarter"), d.getString("year")), list)
}

/** fund-aum.json을 받아 기기에 보관한다. 6시간 안에 받은 것이 있으면 다시 받지 않는다. */
class FundAumRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "fund-aum.json")

    suspend fun cached(): FundAumDataset? = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) parseFundAum(file.readText()) else null }.getOrNull()
    }

    /**
     * 1) GitHub의 fund-aum.json(매일 07:30 수집)을 받는다. 2) 그 자료가 없거나 하루 반 넘게 묵었으면(FreeSIS가 해외 IP를 막아
     * 수집이 실패하는 경우) 기기에서 FreeSIS를 직접 받아 만든다. 3) 둘 다 안 되면 기기에 있는 자료를 보여 준다.
     * 어느 쪽이든 결과를 보관해 6시간 동안은 다시 받지 않는다(직접 받기가 막힌 망에서 매번 기다리지 않도록).
     */
    suspend fun load(force: Boolean = false): FundAumDataset = withContext(Dispatchers.IO) {
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS
        if (!force && fresh) runCatching { return@withContext parseFundAum(file.readText()) }
        var text: String? = runCatching { download(DATA_URL) }.getOrNull()
        var data: FundAumDataset? = text?.let { runCatching { parseFundAum(it) }.getOrNull() }
        if (data == null || isStale(data.generatedAt)) {
            val direct = runCatching { FreeSisDirect.build(FreeSisDirect.networkPost()) }.getOrNull()
            val directData = direct?.let { runCatching { parseFundAum(it) }.getOrNull() }
            if (directData != null) {
                text = direct
                data = directData
            }
        }
        if (data == null || text == null) {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis()) // 6시간 동안은 다시 시도하지 않는다
                return@withContext parseFundAum(file.readText())
            }
            throw IllegalStateException("펀드 순자산 자료를 받지 못했습니다. 인터넷 연결을 확인해 주세요.")
        }
        File(context.filesDir, "fund-aum.json.tmp").apply { writeText(text); renameTo(file) }
        data
    }

    private fun download(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code !in 200..299) error("서버 응답 $code")
            val stream = if ("gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val STALE_HOURS = 30L

        /** 자료를 만든 지 [STALE_HOURS]시간이 넘었으면 true. 시각을 읽지 못해도 true(직접 받아 보게 한다). */
        internal fun isStale(generatedAt: String, now: java.time.Instant = java.time.Instant.now()): Boolean {
            val t = runCatching { java.time.OffsetDateTime.parse(generatedAt).toInstant() }.getOrNull() ?: return true
            return java.time.Duration.between(t, now).toHours() >= STALE_HOURS
        }

        const val DATA_URL = "https://raw.githubusercontent.com/hdlee73/FSS_insights/fund-data/fund-aum.json"
        private const val REFRESH_MS = 6L * 60 * 60 * 1000
    }
}
