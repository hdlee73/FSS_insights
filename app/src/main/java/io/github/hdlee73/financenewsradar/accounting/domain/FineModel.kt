package io.github.hdlee73.financenewsradar.accounting.domain

enum class Level(val label: String, val points: Int) { HIGH("상", 3), MID("중", 2), LOW("하", 1) }

/** 외감규정 [별표 8] 과징금 부과기준 + 법정 상한 + 자본시장법 병과 조정 */
object FineModel {
    private const val EOK = 100_000_000L

    /** 위법행위 중요도 점수 → (회사 부과기준율 %, 감사인·회사관계자 부과기준율 %) */
    fun rate(score: Double): Pair<Double, Double> = when {
        score >= 2.6 -> 20.0 to 500.0
        score >= 2.4 -> 15.0 to 350.0
        score >= 2.2 -> 12.5 to 300.0
        score >= 2.0 -> 10.0 to 250.0
        score >= 1.8 -> 7.5 to 200.0
        score >= 1.6 -> 5.0 to 150.0
        score >= 1.4 -> 3.5 to 100.0
        else -> 2.0 to 50.0
    }

    fun sizeLevel(fineMultiple: Double): Level = when { fineMultiple >= 16 -> Level.HIGH; fineMultiple >= 8 -> Level.MID; else -> Level.LOW }
    fun kindLevel(k: CompanyKind): Level = when (k) {
        CompanyKind.KOSPI, CompanyKind.KOSDAQ -> Level.HIGH
        CompanyKind.KONEX, CompanyKind.REPORTING, CompanyKind.LARGE_UNLISTED -> Level.MID
        CompanyKind.OTHER -> Level.LOW
    }

    data class ScoreResult(val score: Double, val lines: List<String>)

    /** 점수 = Σ(항목 중요도 점수 × 가중치). 중과실이면 모든 항목을 '하(1점)'로 본다. */
    fun score(content: Level, size: Level, kind: Level, market: Level, motive: Motive): ScoreResult {
        val gross = motive == Motive.GROSS_NEGLIGENCE
        fun p(l: Level) = if (gross) 1 else l.points
        val s = p(content) * 0.4 + p(size) * 0.2 + p(kind) * 0.2 + p(market) * 0.2
        val rounded = Math.round(s * 100) / 100.0
        val lines = listOf(
            "위법행위 내용 ${if (gross) "하" else content.label}(${p(content)}점) × 0.4",
            "위반 규모 ${if (gross) "하" else size.label}(${p(size)}점) × 0.2",
            "회사 유형 ${if (gross) "하" else kind.label}(${p(kind)}점) × 0.2",
            "시장에 미치는 영향 ${if (gross) "하" else market.label}(${p(market)}점) × 0.2" + if (gross) "  ※중과실: 전 항목 '하'" else ""
        )
        return ScoreResult(rounded, lines)
    }

    data class FineInputs(
        val actorLabel: String,
        val baseWon: Long,
        /** 기준금액×부과기준율로 계산한 기본과징금 */
        val basicWon: Long,
        val basicNote: String,
        val motive: Motive,
        val upIds: Set<FineAdj>,
        val upFreePct: Int,
        val years: Int,
        val contentHigh: Boolean,
        val isCompanyRate: Boolean,
        val downs: Set<FineAdj>,
        val downFreePct: Int,
        val statutoryCapWon: Long?,
        val capmFineWon: Long?
    )

    data class FineResult(
        val basic: Long,
        val aggravated: Long,
        val reducedBeforeFinal: Long,
        val decided: Long,
        val afterCapm: Long,
        val capped: Boolean,
        val lines: List<String>
    )

    private fun cut(won: Long) = won / 100_000 * 100_000  // 10만원 미만 절사

    fun compute(i: FineInputs): FineResult {
        val lines = mutableListOf<String>()
        val basic = i.basicWon
        lines += "① 기본과징금 = ${i.basicNote} = ${Format.won(basic)}"
        // 가중
        var add = 0.0
        if (FineAdj.UP50 in i.upIds) { add += basic * 0.5; lines += "  가중(재위반·방해·손해 등) +50% 이하 → +${Format.won((basic * 0.5).toLong())}" }
        if (FineAdj.UP20 in i.upIds) { add += basic * 0.2; lines += "  가중(내부회계 취약) +20% 이하 → +${Format.won((basic * 0.2).toLong())}" }
        if (FineAdj.UP_FREE in i.upIds && i.upFreePct > 0) { val a = basic * i.upFreePct / 100.0; add += a; lines += "  가중(통념상 부족) +${i.upFreePct}% → +${Format.won(a.toLong())}" }
        if (FineAdj.UP_YEARS in i.upIds) {
            val unit = if (i.motive == Motive.INTENT) 0.30 else 0.20
            val over = if (i.motive == Motive.INTENT) i.years - 1 else i.years - 2
            if (over > 0) {
                val a = minOf(basic * unit * over, basic * i.years.toDouble())
                add += a
                lines += "  가중(위반 사업연도 초과) 기본과징금의 ${(unit * 100).toInt()}% × ${over}개 사업연도 → +${Format.won(a.toLong())}"
            }
        }
        if (i.contentHigh) {
            val deduct = if (i.isCompanyRate) 0.10 else 2.50
            val a = minOf(maxOf(basic - i.baseWon * deduct, 0.0), basic / 2.0)
            if (a > 0) { add += a; lines += "  가중(위법행위 내용 중요도 '상'): 기본과징금 − 기준금액×${trim(deduct * 100)}% 범위, 기본과징금의 1/2 이내 → +${Format.won(a.toLong())}" }
        }
        val aggravated = basic + add.toLong()
        if (add > 0) lines += "② 가중 후 = ${Format.won(aggravated)}"
        // 감경: 같은 구분(50%/30%) 안에서는 하나만 인정, 구분 간은 합산
        var down = 0.0
        if (FineAdj.DOWN50 in i.downs) down += 0.5
        if (FineAdj.DOWN30 in i.downs) down += 0.3
        if (i.downFreePct > 0) down += i.downFreePct / 100.0
        down = down.coerceAtMost(1.0)
        val reduced = (aggravated * (1.0 - down)).toLong()
        if (down > 0) lines += "③ 감경 ${trim(down * 100)}% 이하 적용(최대치 가정) → ${Format.won(reduced)}"
        var decided = reduced
        if (FineAdj.DOWN70_FINAL in i.downs) {
            decided = (reduced * 0.3).toLong()
            lines += "④ 결정금액 감면(자진 수정공시 등) 70% 이하 감경 가정 → ${Format.won(decided)}"
        }
        var capped = false
        i.statutoryCapWon?.let { cap -> if (decided > cap) { decided = cap; capped = true; lines += "⑤ 법정 상한 ${Format.won(cap)} 초과분 제한" } }
        decided = cut(decided)
        var after = decided
        i.capmFineWon?.let { c ->
            when {
                c >= decided -> { after = 0; lines += "⑥ 자본시장법 과징금 ${Format.won(c)} ≥ 산정액 → 외감법 과징금을 부과하지 않을 수 있음" }
                c > 0 -> { after = cut(decided - c); lines += "⑥ 자본시장법 과징금 ${Format.won(c)} 부과분 공제 → 차액 ${Format.won(after)}만 부과" }
            }
        }
        return FineResult(basic, aggravated, reduced, decided, after, capped, lines)
    }

    /** 자본시장법 제429조제3항 상한(참고). 거래되지 않으면 20억, 거래되면 일평균거래금액의 10%를 10억~20억으로 제한 */
    fun capm429(avgDailyTradingWon: Long?, traded: Boolean): Long =
        if (!traded || avgDailyTradingWon == null) 20 * EOK else (avgDailyTradingWon / 10).coerceIn(10 * EOK, 20 * EOK)

    private fun trim(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)
}
