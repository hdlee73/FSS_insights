package io.github.hdlee73.financenewsradar.accounting.domain

enum class Motive(val label: String) { INTENT("고의"), GROSS_NEGLIGENCE("중과실"), NEGLIGENCE("과실") }

data class SimInput(
    val violationWon: Long,
    val motive: Motive,
    /** 감사인에게 주는 상한 계산용: 해당 감사로 받은 감사보수(원). 없으면 null */
    val auditFeeWon: Long? = null,
    /** 자본시장법 제429조제3항용: 직전 사업연도 증권시장 일평균거래금액(원). 주식이 거래되지 않으면 null */
    val avgDailyTradingWon: Long? = null,
    val sharesTraded: Boolean = true
)

data class LimitResult(
    val label: String,
    val basis: String,
    val upperWon: Long?,
    val applicable: Boolean,
    val formula: String,
    val note: String = ""
)

/**
 * 법 조문에 적힌 과징금 '상한'만 계산한다(확정 계산).
 * 실제 부과금액은 시행령 별표의 부과기준율·가중·감경과 증권선물위원회·금융위원회 재량으로 정해지므로 이 값보다 작거나 부과되지 않을 수 있다.
 */
object PenaltyCalculator {
    private const val EOK = 100_000_000L

    fun limits(input: SimInput): List<LimitResult> {
        val intentOrGross = input.motive != Motive.NEGLIGENCE
        val companyCap = input.violationWon * 20 / 100
        val officerCap = companyCap * 10 / 100
        val out = mutableListOf<LimitResult>()
        out += LimitResult(
            label = "회사", basis = "외부감사법 제35조제1항 전단",
            upperWon = if (intentOrGross) companyCap else null, applicable = intentOrGross,
            formula = "회계처리기준과 달리 작성된 금액 ${Format.won(input.violationWon)} × 20%",
            note = if (intentOrGross) "" else "고의 또는 중대한 과실이 아니면 이 조항의 과징금 대상이 아닙니다."
        )
        out += LimitResult(
            label = "회사관계자(임원·회계담당자)", basis = "외부감사법 제35조제1항 후단",
            upperWon = if (intentOrGross) officerCap else null, applicable = intentOrGross,
            formula = "회사에 부과하는 과징금의 10% 이내 (회사 상한 기준 ${Format.won(companyCap)} × 10%)",
            note = "위법행위를 알았거나 현저한 주의의무 위반으로 방지하지 못한 자에 한함."
        )
        val fee = input.auditFeeWon
        out += LimitResult(
            label = "감사인", basis = "외부감사법 제35조제2항",
            upperWon = if (fee != null && intentOrGross) fee * 5 else null, applicable = fee != null && intentOrGross,
            formula = if (fee != null) "해당 감사로 받은 보수 ${Format.won(fee)} × 5배" else "감사보수를 입력하면 계산합니다.",
            note = "감사인이 고의 또는 중과실로 회계감사기준을 위반해 감사보고서를 작성한 경우."
        )
        val traded = input.sharesTraded && input.avgDailyTradingWon != null
        val capm = when {
            !input.sharesTraded -> 20 * EOK
            input.avgDailyTradingWon != null -> (input.avgDailyTradingWon / 10).coerceIn(10 * EOK, 20 * EOK)
            else -> null
        }
        out += LimitResult(
            label = "사업보고서 허위기재(참고)", basis = "자본시장법 제429조제3항",
            upperWon = capm, applicable = capm != null,
            formula = when {
                !input.sharesTraded -> "주식이 증권시장에서 거래되지 않는 경우 20억원 이내"
                traded -> "일평균거래금액 ${Format.won(input.avgDailyTradingWon)} × 10% (최소 10억원, 최대 20억원)"
                else -> "일평균거래금액을 입력하면 계산합니다."
            },
            note = "사업보고서 제출대상법인이 사업보고서 중요사항을 거짓 기재한 경우의 공시위반 과징금(외감법 과징금과 별개 근거)."
        )
        return out
    }

    /** 제척기간 확인: 외감법 8년(감리 개시 시 중단), 자본시장법 5년 */
    fun limitationYears(): Pair<Int, Int> = 8 to 5
}
