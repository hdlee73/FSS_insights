package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** 증시 동향 탭의 종목(지수·주식·ETF). [symbol]은 Yahoo 형식(005930.KS, ^SOX, AAPL). */
data class Instrument(val symbol: String, val name: String, val currency: String, val type: String) {
    val isIndex: Boolean get() = type == TYPE_INDEX
    val isKorean: Boolean get() = currency == "KRW"

    companion object {
        const val TYPE_INDEX = "INDEX"
        const val TYPE_ETF = "ETF"
        const val TYPE_EQUITY = "EQUITY"
        const val TYPE_FX = "FX"
    }
}

data class Quote(val price: Double, val previous: Double?, val timeSeconds: Long, val market: String) {
    val change: Double? get() = previous?.takeIf { it > 0 }?.let { price - it }
    val changePercent: Double? get() = previous?.takeIf { it > 0 }?.let { (price - it) / it * 100 }
}

/** 최근 1년 일별 종가와 기간 최고가·최저가. */
data class PriceHistory(val closes: List<Double>, val high: Double, val low: Double)

/** 로컬에 저장하는 관심종목. 저장된 값이 없을 때만 기본 종목을 보여 준다. */
class WatchlistStore(context: Context) {
    private val preferences = context.getSharedPreferences("market_watchlist", Context.MODE_PRIVATE)

    fun load(): List<Instrument> {
        val raw = preferences.getString(ITEMS, null) ?: return DEFAULT_WATCH
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Instrument(o.getString("symbol"), o.getString("name"), o.getString("currency"), o.getString("type"))
            }
        }.getOrDefault(DEFAULT_WATCH)
    }

    fun save(items: List<Instrument>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("symbol", it.symbol).put("name", it.name).put("currency", it.currency).put("type", it.type))
        }
        preferences.edit().putString(ITEMS, array.toString()).apply()
    }

    private companion object {
        const val ITEMS = "items"
    }
}

val DEFAULT_WATCH = listOf(
    Instrument("005930.KS", "삼성전자", "KRW", Instrument.TYPE_EQUITY),
    Instrument("000660.KS", "SK하이닉스", "KRW", Instrument.TYPE_EQUITY),
    // SK하이닉스는 미국 상장 ADR이 없어(확인 필요) 같은 메모리 반도체인 마이크론을 대신 둔다.
    Instrument("MU", "마이크론 · Micron (하이닉스 ADR 대용)", "USD", Instrument.TYPE_EQUITY),
    Instrument("^SOX", "필라델피아 반도체 지수", "USD", Instrument.TYPE_INDEX),
    Instrument("069500.KS", "KODEX 200", "KRW", Instrument.TYPE_ETF)
)

/** 화면 위쪽 시장 지수·환율 패널. */
val MARKET_PANEL = listOf(
    Instrument("^KS11", "코스피", "KRW", Instrument.TYPE_INDEX),
    Instrument("^KQ11", "코스닥", "KRW", Instrument.TYPE_INDEX),
    Instrument("^IXIC", "나스닥", "USD", Instrument.TYPE_INDEX),
    Instrument("^GSPC", "S&P 500", "USD", Instrument.TYPE_INDEX),
    Instrument("KRW=X", "원/달러", "KRW", Instrument.TYPE_FX)
)

/** 검색 전에 보여 주는 자주 찾는 종목. */
val POPULAR_INSTRUMENTS = listOf(
    Instrument("005930.KS", "삼성전자", "KRW", Instrument.TYPE_EQUITY),
    Instrument("000660.KS", "SK하이닉스", "KRW", Instrument.TYPE_EQUITY),
    Instrument("035420.KS", "NAVER", "KRW", Instrument.TYPE_EQUITY),
    Instrument("035720.KS", "카카오", "KRW", Instrument.TYPE_EQUITY),
    Instrument("005380.KS", "현대차", "KRW", Instrument.TYPE_EQUITY),
    Instrument("069500.KS", "KODEX 200", "KRW", Instrument.TYPE_ETF),
    Instrument("360750.KS", "TIGER 미국S&P500", "KRW", Instrument.TYPE_ETF),
    Instrument("133690.KS", "TIGER 미국나스닥100", "KRW", Instrument.TYPE_ETF),
    Instrument("^KS11", "코스피", "KRW", Instrument.TYPE_INDEX),
    Instrument("^KQ11", "코스닥", "KRW", Instrument.TYPE_INDEX),
    Instrument("AAPL", "애플 · Apple", "USD", Instrument.TYPE_EQUITY),
    Instrument("MSFT", "마이크로소프트 · Microsoft", "USD", Instrument.TYPE_EQUITY),
    Instrument("NVDA", "엔비디아 · NVIDIA", "USD", Instrument.TYPE_EQUITY),
    Instrument("TSLA", "테슬라 · Tesla", "USD", Instrument.TYPE_EQUITY),
    Instrument("MU", "마이크론 · Micron", "USD", Instrument.TYPE_EQUITY),
    Instrument("VOO", "Vanguard S&P 500 ETF", "USD", Instrument.TYPE_ETF),
    Instrument("SPY", "SPDR S&P 500 ETF", "USD", Instrument.TYPE_ETF),
    Instrument("QQQ", "Invesco QQQ ETF", "USD", Instrument.TYPE_ETF),
    Instrument("SOXX", "iShares 반도체 ETF", "USD", Instrument.TYPE_ETF),
    Instrument("^IXIC", "나스닥 종합지수", "USD", Instrument.TYPE_INDEX),
    Instrument("^GSPC", "S&P 500 지수", "USD", Instrument.TYPE_INDEX),
    Instrument("^DJI", "다우존스 지수", "USD", Instrument.TYPE_INDEX),
    Instrument("^NDX", "나스닥 100 지수", "USD", Instrument.TYPE_INDEX),
    Instrument("^SOX", "필라델피아 반도체 지수", "USD", Instrument.TYPE_INDEX),
    Instrument("^VIX", "VIX 변동성 지수", "USD", Instrument.TYPE_INDEX)
)

/** 지수 이름의 별칭(검색용). */
private val INDEX_ALIASES = mapOf(
    "^IXIC" to "nasdaq composite ixic 나스닥종합 나스닥지수",
    "^GSPC" to "s&p 500 sp500 snp 에스앤피 gspc",
    "^DJI" to "dow jones djia 다우 다우지수",
    "^NDX" to "nasdaq 100 nasdaq100 나스닥100 ndx",
    "^SOX" to "philadelphia semiconductor sox 필라델피아 반도체지수",
    "^VIX" to "volatility vix 변동성 공포지수",
    "^KS11" to "kospi 코스피",
    "^KQ11" to "kosdaq 코스닥"
)

private fun normalized(value: String) = value.replace(Regex("\\s+"), "").lowercase(Locale.ROOT)

/** 내장 목록에서 이름·코드·별칭으로 찾는다(네트워크 없이). */
fun searchLocal(query: String): List<Instrument> {
    val key = normalized(query)
    if (key.isEmpty()) return emptyList()
    return POPULAR_INSTRUMENTS.filter {
        normalized(it.name).contains(key) || normalized(it.symbol).contains(key) ||
            normalized(INDEX_ALIASES[it.symbol].orEmpty()).contains(key)
    }
}

/** 시세 제공처 응답의 "1,234" 형식 숫자를 읽는다. */
internal fun parseNumber(text: String): Double = text.replace(",", "").trim().toDouble()

/** NAVER 등락 방향 코드(4·5=하락, 1·2=상승, 3=보합)를 반영한 전일 대비 값. */
internal fun signedChange(amount: Double, directionCode: String): Double = when (directionCode) {
    "4", "5" -> -kotlin.math.abs(amount)
    "1", "2" -> kotlin.math.abs(amount)
    "3" -> 0.0
    else -> amount
}

/**
 * 공개 시세 조회(API 키 없음). 한국 종목·지수는 NAVER, 미국·환율은 Yahoo Finance를 쓰며
 * 정식 계약형 API가 아니므로 지연·중단·호출 제한이 있을 수 있다.
 */
class MarketClient(private val context: Context) {

    private fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 FSSInsights/1.0")
        c.setRequestProperty("Accept", "application/json,text/xml,*/*")
        c.setRequestProperty("Referer", "https://m.stock.naver.com/")
        try {
            if (c.responseCode != 200) error("시세 제공처 응답 ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > 8_000_000) error("응답 크기 초과")
                }
            }
            val charset = if (c.contentType.orEmpty().lowercase(Locale.ROOT).contains("euc-kr")) charset("EUC-KR") else Charsets.UTF_8
            return String(out.toByteArray(), charset)
        } finally {
            c.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun checkedSymbol(raw: String): String {
        require(Regex("[A-Za-z0-9^.=\\-]{1,32}").matches(raw)) { "잘못된 종목 코드" }
        return raw.uppercase(Locale.ROOT)
    }

    private fun tradeTime(text: String): Long {
        if (text.isBlank()) return 0
        return runCatching { LocalDateTime.parse(text.replace(" ", "T")).atZone(ZoneId.of("Asia/Seoul")).toEpochSecond() }
            .recoverCatching { OffsetDateTime.parse(text).toEpochSecond() }
            .getOrDefault(0)
    }

    suspend fun quote(raw: String): Quote = withContext(Dispatchers.IO) {
        val symbol = checkedSymbol(raw)
        when {
            symbol == "^KS11" || symbol == "^KQ11" ->
                runCatching { naverQuote("https://m.stock.naver.com/api/index/${if (symbol == "^KS11") "KOSPI" else "KOSDAQ"}/basic") }
                    .getOrElse { yahooQuote(symbol) }
            Regex("[A-Z0-9]{6}\\.(KS|KQ)").matches(symbol) ->
                runCatching { naverQuote("https://m.stock.naver.com/api/stock/${symbol.substring(0, 6)}/basic") }
                    .getOrElse { yahooQuote(symbol) }
            else -> yahooQuote(symbol)
        }
    }

    private fun naverQuote(url: String): Quote {
        val o = JSONObject(get(url))
        val price = parseNumber(o.getString("closePrice"))
        val direction = o.optJSONObject("compareToPreviousPrice")?.optString("code").orEmpty()
        val change = signedChange(parseNumber(o.getString("compareToPreviousClosePrice")), direction)
        val previous = price - change
        if (!price.isFinite() || price <= 0 || !previous.isFinite() || previous <= 0) error("가격 없음")
        return Quote(price, previous, tradeTime(o.optString("localTradedAt")), o.optString("marketStatus"))
    }

    private fun yahooChart(symbol: String, range: String): JSONObject {
        var last: Exception? = null
        for (host in listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")) {
            try {
                val root = JSONObject(get("https://$host/v8/finance/chart/${enc(symbol)}?range=$range&interval=1d"))
                val result = root.getJSONObject("chart").optJSONArray("result")
                if (result == null || result.length() == 0) error("해당 종목 시세가 없습니다")
                return result.getJSONObject(0)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("시세 연결 실패")
    }

    private fun yahooQuote(symbol: String): Quote {
        val chart = yahooChart(symbol, "5d")
        val meta = chart.getJSONObject("meta")
        val price = meta.getDouble("regularMarketPrice")
        if (!price.isFinite() || price <= 0) error("유효한 가격 없음")
        val marketTime = meta.optLong("regularMarketTime", 0)
        // 지수·환율은 일별 봉에서 전일 종가를 구해 '오늘' 변동이 5일 전 기준이 되지 않게 한다.
        var previous = if (symbol.startsWith("^") || symbol.endsWith("=X")) Double.NaN else meta.optDouble("previousClose", Double.NaN)
        if (!previous.isFinite()) {
            val zone = ZoneId.of(meta.optString("exchangeTimezoneName", "America/New_York"))
            val latest = java.time.Instant.ofEpochSecond(marketTime).atZone(zone).toLocalDate()
            val timestamps = chart.optJSONArray("timestamp")
            val closes = chart.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
            for (i in 0 until (timestamps?.length() ?: 0)) {
                val day = java.time.Instant.ofEpochSecond(timestamps!!.getLong(i)).atZone(zone).toLocalDate()
                val close = closes.optDouble(i, Double.NaN)
                if (day.isBefore(latest) && close.isFinite() && close > 0) previous = close
            }
        }
        return Quote(price, previous.takeIf { it.isFinite() }, marketTime, meta.optString("exchangeName"))
    }

    /** 최근 1년 일별 종가. 1시간 동안은 캐시 파일을 쓴다. */
    suspend fun history(raw: String): PriceHistory = withContext(Dispatchers.IO) {
        val symbol = checkedSymbol(raw)
        val file = File(context.cacheDir, "market-history-" + symbol.replace(Regex("[^A-Za-z0-9]"), "_") + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 3_600_000) {
            runCatching { return@withContext decodeHistory(JSONObject(file.readText())) }
        }
        val result = runCatching { yahooHistory(symbol) }.getOrElse {
            if (!Regex("[A-Z0-9]{6}\\.(KS|KQ)").matches(symbol)) throw it
            naverHistory(symbol.substring(0, 6))
        }
        runCatching { file.writeText(encodeHistory(result).toString()) }
        result
    }

    private fun encodeHistory(h: PriceHistory) =
        JSONObject().put("closes", JSONArray(h.closes)).put("high", h.high).put("low", h.low)

    private fun decodeHistory(o: JSONObject): PriceHistory {
        val a = o.getJSONArray("closes")
        return PriceHistory((0 until a.length()).map { a.getDouble(it) }, o.getDouble("high"), o.getDouble("low"))
    }

    private fun yahooHistory(symbol: String): PriceHistory {
        val chart = yahooChart(symbol, "1y")
        val values = chart.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val closes = values.getJSONArray("close")
        val highs = values.getJSONArray("high")
        val lows = values.getJSONArray("low")
        val points = ArrayList<Double>()
        var high = -Double.MAX_VALUE
        var low = Double.MAX_VALUE
        for (i in 0 until closes.length()) {
            val close = closes.optDouble(i, Double.NaN)
            if (!close.isFinite()) continue
            highs.optDouble(i, close).takeIf { it.isFinite() }?.let { high = maxOf(high, it) }
            lows.optDouble(i, close).takeIf { it.isFinite() }?.let { low = minOf(low, it) }
            points += close
        }
        if (points.size < 2) error("1년 차트 자료가 부족합니다")
        return PriceHistory(points, high, low)
    }

    /** NAVER 일봉 XML: item data="날짜|시가|고가|저가|종가|거래량". */
    private fun naverHistory(code: String): PriceHistory {
        val xml = get("https://fchart.stock.naver.com/sise.nhn?symbol=$code&timeframe=day&count=260&requestType=0")
        val points = ArrayList<Double>()
        var high = -Double.MAX_VALUE
        var low = Double.MAX_VALUE
        val cutoff = java.time.LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(1)
        for (m in Regex("<item data=\"([^\"]+)\"").findAll(xml)) {
            val v = m.groupValues[1].split("|")
            if (v.size < 5) continue
            val day = runCatching { java.time.LocalDate.parse(v[0], java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: continue
            if (day.isBefore(cutoff)) continue
            val close = parseNumber(v[4])
            if (close <= 0) continue
            high = maxOf(high, parseNumber(v[2]))
            low = minOf(low, parseNumber(v[3]))
            points += close
        }
        if (points.size < 2) error("차트 자료 없음")
        return PriceHistory(points, high, low)
    }

    private fun etfCatalog(): List<Instrument> {
        val prefs = context.getSharedPreferences("market_catalog", Context.MODE_PRIVATE)
        val cached = prefs.getString("etfs", "").orEmpty()
        fun decode(text: String): List<Instrument> {
            val a = JSONArray(text)
            return (0 until a.length()).map { a.getJSONObject(it) }.map {
                Instrument(it.getString("symbol"), it.getString("name"), "KRW", Instrument.TYPE_ETF)
            }
        }
        if (cached.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("time", 0) < 86_400_000) return decode(cached)
        return try {
            val items = JSONObject(get("https://finance.naver.com/api/sise/etfItemList.nhn")).getJSONObject("result").getJSONArray("etfItemList")
            val out = JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val code = item.getString("itemcode").uppercase(Locale.ROOT)
                if (Regex("[A-Z0-9]{6}").matches(code)) out.put(JSONObject().put("symbol", "$code.KS").put("name", item.getString("itemname")))
            }
            if (out.length() == 0) error("ETF 목록 없음")
            prefs.edit().putString("etfs", out.toString()).putLong("time", System.currentTimeMillis()).apply()
            decode(out.toString())
        } catch (e: Exception) {
            if (cached.isNotEmpty()) decode(cached) else throw e
        }
    }

    /** 이름·코드로 한국/미국 주식·ETF·지수를 찾는다. 내장 목록 → NAVER ETF·자동완성 → Yahoo 순서. */
    suspend fun search(query: String): List<Instrument> = withContext(Dispatchers.IO) {
        require(query.length <= 80) { "검색어가 너무 깁니다" }
        val key = normalized(query)
        val out = LinkedHashMap<String, Instrument>()
        searchLocal(query).forEach { out[it.symbol] = it }
        var last: Exception? = null
        runCatching {
            etfCatalog().filter { normalized(it.name).contains(key) || normalized(it.symbol).contains(key) }
                .sortedBy { val n = normalized(it.name); if (n == key) 0 else if (n.startsWith(key)) 1 else 2 }
                .forEach { out.putIfAbsent(it.symbol, it) }
        }.onFailure { last = it as? Exception }
        runCatching {
            collectDomestic(JSONObject(get("https://m.stock.naver.com/front-api/search/autoComplete?query=${enc(query)}&target=stock,index")), out)
        }.onFailure { last = it as? Exception }
        val hasHangul = Regex(".*[가-힣].*").matches(query)
        if (!(out.isNotEmpty() && hasHangul)) {
            runCatching {
                val path = "/v1/finance/search?q=${enc(query)}&quotesCount=20&newsCount=0"
                val body = runCatching { get("https://query1.finance.yahoo.com$path") }.getOrElse { get("https://query2.finance.yahoo.com$path") }
                val quotes = JSONObject(body).optJSONArray("quotes") ?: JSONArray()
                for (i in 0 until quotes.length()) {
                    val v = quotes.getJSONObject(i)
                    val kind = v.optString("quoteType")
                    val symbol = v.optString("symbol")
                    val korean = symbol.endsWith(".KS") || symbol.endsWith(".KQ") || symbol == "^KS11" || symbol == "^KQ11"
                    val us = v.optString("exchange") in US_EXCHANGES ||
                        (kind == "INDEX" && Regex("\\^[A-Z0-9]{2,8}").matches(symbol))
                    if ((korean || us) && kind in setOf("EQUITY", "ETF", "INDEX")) {
                        out.putIfAbsent(symbol, Instrument(symbol, v.optString("shortname", v.optString("longname", symbol)), if (korean) "KRW" else "USD", kind))
                    }
                }
            }.onFailure { last = it as? Exception }
        }
        if (out.isEmpty() && last != null) throw IllegalStateException("검색 연결 실패 · 종목 코드로 다시 검색해 주세요", last)
        out.values.toList()
    }

    private fun collectDomestic(value: Any?, out: MutableMap<String, Instrument>) {
        when (value) {
            is JSONObject -> {
                val code = value.optString("code")
                val name = value.optString("name")
                val reuters = value.optString("reutersCode")
                val isStock = value.optString("url").contains("/domestic/stock/") || Regex("[A-Z0-9]{6}\\.(KS|KQ)").matches(reuters)
                if (Regex("[A-Z0-9]{6}").matches(code) && name.isNotEmpty() && isStock) {
                    val symbol = if (Regex("[A-Z0-9]{6}\\.(KS|KQ)").matches(reuters)) reuters
                    else code + if (value.optString("typeName").contains("코스닥")) ".KQ" else ".KS"
                    out.putIfAbsent(symbol, Instrument(symbol, name, "KRW", Instrument.TYPE_EQUITY))
                    return
                }
                for (k in value.keys()) collectDomestic(value.opt(k), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectDomestic(value.opt(i), out)
        }
    }

    private companion object {
        val US_EXCHANGES = setOf("NMS", "NYQ", "NGM", "NCM", "ASE", "PCX", "BTS", "NASDAQ", "NYSE", "NYSEArca")
    }
}
