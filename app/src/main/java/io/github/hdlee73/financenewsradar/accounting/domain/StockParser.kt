package io.github.hdlee73.financenewsradar.accounting.domain

data class Candle(val date: String, val close: Long, val volume: Long)

data class StockSummary(
    val code: String,
    val name: String,
    val market: String,
    val lastClose: Long?,
    val lastDate: String?,
    /** 산정 기간 일평균거래금액(종가×거래량 근사, 원) */
    val avgTradingWon: Long?,
    val tradingDays: Int,
    /** 산정 기간 yyyy-MM-dd */
    val from: String,
    val to: String,
    /** 산정 기간 거래금액 합계(원) */
    val sumTradingWon: Long = 0
)

/** 네이버 증권 응답 해석(순수 함수: 단위 테스트 가능) */
object StockParser {
    private val ROW = Regex("""\[\s*"(\d{8})"\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)""")

    fun candles(text: String): List<Candle> =
        ROW.findAll(text).map { m -> Candle(m.groupValues[1], m.groupValues[5].toLong(), m.groupValues[6].toLong()) }.toList()

    /** 일평균거래금액 = Σ(종가×거래량) / 거래일수 */
    fun avgTradingWon(c: List<Candle>): Long? {
        val days = c.filter { it.volume > 0 }
        if (days.isEmpty()) return null
        return days.sumOf { it.close * it.volume } / days.size
    }

    /** 거래량이 있는 날만 */
    fun tradingDays(c: List<Candle>): List<Candle> = c.filter { it.volume > 0 }

    /** "2025-01-01" / "20250101" → "20250101". 형식이 틀리면 null */
    fun ymd(text: String): String? {
        val d = text.trim().replace("-", "").replace(".", "").replace("/", "")
        if (!Regex("""\d{8}""").matches(d)) return null
        return runCatching { java.time.LocalDate.parse(d, java.time.format.DateTimeFormatter.BASIC_ISO_DATE); d }.getOrNull()
    }

    fun dash(ymd: String): String = if (ymd.length == 8) "${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6)}" else ymd

    fun market(typeName: String): String = when {
        typeName.contains("코스닥", true) || typeName.contains("KOSDAQ", true) -> "코스닥"
        typeName.contains("코넥스", true) || typeName.contains("KONEX", true) -> "코넥스"
        typeName.contains("코스피", true) || typeName.contains("KOSPI", true) -> "유가증권"
        else -> typeName
    }

    fun kindFor(market: String): CompanyKind? = when (market) {
        "코스닥" -> CompanyKind.KOSDAQ
        "코넥스" -> CompanyKind.KONEX
        "유가증권" -> CompanyKind.KOSPI
        else -> null
    }
}
