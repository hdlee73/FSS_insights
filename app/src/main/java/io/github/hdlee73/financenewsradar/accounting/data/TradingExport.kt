package io.github.hdlee73.financenewsradar.accounting.data

import io.github.hdlee73.financenewsradar.accounting.domain.Candle
import io.github.hdlee73.financenewsradar.accounting.domain.StockParser
import io.github.hdlee73.financenewsradar.accounting.domain.StockSummary

/** 일평균거래금액 산정 내역을 엑셀 시트로 만든다(요약 + 일별 내역). */
object TradingExport {
    fun sheets(s: StockSummary, candles: List<Candle>): List<XlsxWriter.Sheet> {
        val traded = StockParser.tradingDays(candles)
        val summary = XlsxWriter.Sheet(
            name = "요약",
            header = listOf("항목", "값"),
            rows = listOf(
                listOf("종목", "${s.name} (${s.code})"),
                listOf("시장", s.market),
                listOf("산정 기간", "${s.from} ~ ${s.to}"),
                listOf("거래일 수(거래량 있는 날)", traded.size.toLong()),
                listOf("거래금액 합계(원)", s.sumTradingWon),
                listOf("일평균거래금액(원)", s.avgTradingWon ?: 0L),
                listOf("산식", "일평균거래금액 = Σ(일별 종가 × 거래량) ÷ 거래일 수"),
                listOf("자본시장법 제429조제3항 상한 참고", "일평균거래금액의 10%(최소 10억원, 최대 20억원)"),
                listOf("출처", "네이버 증권 일별 시세(종가×거래량 근사치, 실제 거래대금과 다를 수 있음)"),
            ),
            widths = listOf(28, 60)
        )
        val daily = XlsxWriter.Sheet(
            name = "일별 내역",
            header = listOf("일자", "종가(원)", "거래량(주)", "거래금액(원, 종가×거래량)", "산정 포함"),
            rows = candles.map { c -> listOf(StockParser.dash(c.date), c.close, c.volume, c.close * c.volume, if (c.volume > 0) "포함" else "제외(거래 없음)") },
            widths = listOf(12, 12, 14, 22, 14)
        )
        return listOf(summary, daily)
    }

    fun fileName(s: StockSummary) = "일평균거래금액_${s.name}_${s.from}_${s.to}.xlsx".replace(Regex("""[\\/:*?"<>|]"""), "_")
}
