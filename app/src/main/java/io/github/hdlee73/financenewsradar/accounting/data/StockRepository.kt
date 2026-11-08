package io.github.hdlee73.financenewsradar.accounting.data

import io.github.hdlee73.financenewsradar.accounting.domain.Candle
import io.github.hdlee73.financenewsradar.accounting.domain.StockParser
import io.github.hdlee73.financenewsradar.accounting.domain.StockSummary
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class StockCandidate(val code: String, val name: String, val market: String)

/** 네이버 증권의 공개 시세 응답으로 종목 검색과 직전 사업연도 일평균거래금액을 구한다. 실패 시 예외를 던지므로 호출 측에서 처리한다. */
class StockRepository {

    suspend fun search(query: String): List<StockCandidate> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val body = get("https://ac.stock.naver.com/ac?target=stock&q=" + URLEncoder.encode(q, "UTF-8"))
        val arr = JSONObject(body).optJSONArray("items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val market = StockParser.market(o.optString("typeName"))
            if (StockParser.kindFor(market) == null) return@mapNotNull null  // 해외·기타 제외
            val code = o.optString("code")
            if (code.isEmpty()) null else StockCandidate(code, o.optString("name"), market)
        }.take(8)
    }

    /** [from]~[to](yyyyMMdd) 일별 시세와 그 기간의 일평균거래금액 */
    suspend fun period(c: StockCandidate, from: String, to: String): Pair<StockSummary, List<Candle>> = withContext(Dispatchers.IO) {
        val days = StockParser.candles(chart(c.code, from, to))
        val today = LocalDate.now()
        val recent = StockParser.candles(chart(c.code, today.minusDays(14).toString().replace("-", ""), today.toString().replace("-", "")))
        val last = recent.lastOrNull()
        val traded = StockParser.tradingDays(days)
        StockSummary(
            code = c.code, name = c.name, market = c.market, lastClose = last?.close, lastDate = last?.date,
            avgTradingWon = StockParser.avgTradingWon(days), tradingDays = traded.size,
            from = StockParser.dash(from), to = StockParser.dash(to), sumTradingWon = traded.sumOf { it.close * it.volume }
        ) to days
    }

    private fun chart(code: String, from: String, to: String) =
        get("https://api.finance.naver.com/siseJson.naver?symbol=$code&requestType=1&startTime=$from&endTime=$to&timeframe=day")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "FSS-Insights-Accounting/0.3")
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
