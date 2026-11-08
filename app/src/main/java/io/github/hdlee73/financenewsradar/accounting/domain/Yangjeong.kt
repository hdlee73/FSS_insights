package io.github.hdlee73.financenewsradar.accounting.domain

/**
 * 심사·감리결과 조치양정(외부감사 및 회계 등에 관한 규정 시행세칙 [별표 1]) 계산.
 * 규모금액(<표1>) → 중요성 기준금액 → 규모배수 → 중요도(Ⅰ~Ⅴ) → 조치표 → 가중·감경.
 * 참고용 모의계산이며 실제 판단은 증권선물위원회 등의 몫이다.
 */
enum class VType(val label: String, val short: String, val pctOfScale: Double, val auditorMultiple: Double, val defaultBasis: ScaleBasis) {
    A("A 당기손익·자기자본에 영향", "A", 1.0, 1.0, ScaleBasis.AVG),
    B("B 자산·부채·수익·비용 과대/과소 등(손익 영향 없음)", "B", 4.0, 4.0, ScaleBasis.ASSET),
    C("C 특수관계자·보증·소송 주석", "C", 5.0, 5.0, ScaleBasis.AVG),
    D("D 기타 주석·계정분류", "D", 15.0, 15.0, ScaleBasis.AVG)
}

enum class ScaleBasis(val label: String) { AVG("자산·매출 평균"), ASSET("자산총계"), SALES("매출액") }

enum class CompanyKind(val label: String, val listed: Boolean) {
    KOSPI("유가증권시장 상장", true), KOSDAQ("코스닥 상장", true), KONEX("코넥스 상장", true),
    REPORTING("사업보고서 제출 비상장", false), LARGE_UNLISTED("대형 비상장주식회사", false), OTHER("그 밖의 비상장", false)
}

data class ViolationItem(val type: VType, val basis: ScaleBasis, val amountWon: Long, val motive: Motive)

data class CompanyScale(
    val assetWon: Long,
    val salesWon: Long,
    val kind: CompanyKind,
    val financial: Boolean = false,
    /** 비상장 중 표1 각주(*3)의 예외에 해당하지 않아 규모조정계수 50%를 적용하는 경우 */
    val halfCoefficient: Boolean = false
)

object Yangjeong {
    private const val EOK = 100_000_000.0

    // (구간 최소 억원, 기본계수, 보간율)
    private val BANDS = listOf(
        Triple(0.0, 0.4, 0.0), Triple(100.0, 0.6, 0.001), Triple(300.0, 0.8, 0.0005), Triple(700.0, 1.0, 0.00066667),
        Triple(1000.0, 1.2, 0.0003), Triple(2000.0, 1.5, 0.0001), Triple(5000.0, 1.8, 0.00006), Triple(10000.0, 2.1, 0.00003),
        Triple(20000.0, 2.4, 0.00002), Triple(50000.0, 3.0, 0.000012), Triple(100000.0, 3.6, 0.000007),
        Triple(200000.0, 4.3, 0.00000233), Triple(500000.0, 5.0, 0.000004), Triple(1000000.0, 7.0, 0.000003), Triple(2000000.0, 10.0, 0.0)
    )

    /** 규모조정계수 (금액은 억원) */
    fun coefficient(eok: Double): Double {
        val b = BANDS.last { eok >= it.first }
        return if (b.third == 0.0) b.second else b.second + b.third * (eok - b.first)
    }

    /** 위법행위 한 건에 쓸 규모금액(원). <표1> 마. = 다 ÷ 라 */
    fun scaleAmountWon(c: CompanyScale, basis: ScaleBasis): Long {
        val baseWon = when (basis) {
            ScaleBasis.ASSET -> c.assetWon.toDouble()
            ScaleBasis.SALES -> c.salesWon.toDouble()
            ScaleBasis.AVG -> (c.assetWon + c.salesWon) / 2.0
        }
        if (baseWon <= 0) return 0
        val eok = baseWon / EOK
        var k = coefficient(eok)
        if ((c.kind.listed || c.financial) && eok < 700) k = 1.0
        if (c.halfCoefficient && !c.kind.listed && !c.financial) k *= 0.5
        return (baseWon / k).toLong()
    }

    /** 유형별 중요성 기준금액(원). 감사인 중요성 금액이 있으면 그 배수, 없으면 표준(규모금액의 %) */
    fun thresholdWon(c: CompanyScale, v: ViolationItem, auditorMaterialityWon: Long?): Double =
        if (auditorMaterialityWon != null && auditorMaterialityWon > 0) auditorMaterialityWon * v.type.auditorMultiple
        else scaleAmountWon(c, v.basis) * v.type.pctOfScale / 100.0

    /** 위법동기별 규모배수. D유형은 유형별 6배 제한 */
    fun multiples(c: CompanyScale, items: List<ViolationItem>, auditorMaterialityWon: Long?): Map<Motive, Double> {
        val out = mutableMapOf<Motive, Double>()
        for (m in Motive.entries) {
            var sum = 0.0
            for (t in VType.entries) {
                val rows = items.filter { it.motive == m && it.type == t }
                if (rows.isEmpty()) continue
                // 같은 유형이라도 기준(자산/매출/평균)이 다르면 행별 기준금액으로 각각 나눈 배수를 합산
                var typeMult = rows.sumOf { r ->
                    val th = thresholdWon(c, r, auditorMaterialityWon)
                    if (th > 0) r.amountWon / th else 0.0
                }
                if (t == VType.D) typeMult = minOf(typeMult, 6.0)
                sum += typeMult
            }
            if (sum > 0) out[m] = sum
        }
        return out
    }

    /** 규모배수 → 사다리 위치(0=감경시 최소, 1=Ⅴ … 5=Ⅰ, 6=가중시 최대). 1배 미만은 null */
    fun ladderIndex(multiple: Double): Int? = when {
        multiple >= 16 -> 5
        multiple >= 8 -> 4
        multiple >= 4 -> 3
        multiple >= 2 -> 2
        multiple >= 1 -> 1
        else -> null
    }

    fun gradeLabel(idx: Int): String = when (idx) { 0 -> "감경시 최소"; 1 -> "Ⅴ"; 2 -> "Ⅳ"; 3 -> "Ⅲ"; 4 -> "Ⅱ"; 5 -> "Ⅰ"; else -> "가중시 최대" }

    /** 동기·단계를 한 줄로 비교하기 위한 점수(증권발행제한 개월 수에 준하는 값) */
    private val SCORE = mapOf(
        Motive.INTENT to doubleArrayOf(4.0, 6.0, 8.0, 10.0, 11.0, 12.0, 12.0),
        Motive.GROSS_NEGLIGENCE to doubleArrayOf(1.0, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0),
        Motive.NEGLIGENCE to doubleArrayOf(0.0, 0.2, 0.3, 0.5, 2.0, 4.0, 6.0)
    )
    private fun bonus(m: Motive) = when (m) { Motive.INTENT -> 0.02; Motive.GROSS_NEGLIGENCE -> 0.01; Motive.NEGLIGENCE -> 0.0 }
    fun score(m: Motive, idx: Int): Double = SCORE.getValue(m)[idx.coerceIn(0, 6)] + bonus(m)

    data class Step(val motive: Motive, val idx: Int) {
        val label: String get() = "${motive.label} ${Yangjeong.gradeLabel(idx)}"
    }

    data class Assessment(
        val multiples: Map<Motive, Double>,
        val perMotive: Map<Motive, Int?>,
        val converted: Map<Motive, Double>,
        val before: Step?,
        val after: Step?,
        val base: Step?,
        val final: Step?,
        val netSteps: Int,
        val finalMultiple: Double,
        val fineMultiple: Double,
        val notes: List<String>
    )

    /**
     * @param netSteps 가중(+)·감경(−) 사유의 합계 단계 수
     */
    fun assess(c: CompanyScale, items: List<ViolationItem>, auditorMaterialityWon: Long?, netSteps: Int): Assessment {
        val notes = mutableListOf<String>()
        val mult = multiples(c, items, auditorMaterialityWon)
        val per = mult.mapValues { ladderIndex(it.value) }
        val atMotive = per.entries.filter { it.value != null }.map { Step(it.key, it.value!!) }
        val before = atMotive.maxByOrNull { score(it.motive, it.idx) }
        var converted = emptyMap<Motive, Double>()
        var after: Step? = before
        val mi = mult[Motive.INTENT] ?: 0.0
        val mg = mult[Motive.GROSS_NEGLIGENCE] ?: 0.0
        if (mi > 0 && mg > 0) {
            converted = mapOf(Motive.INTENT to mi + mg / 8.0, Motive.GROSS_NEGLIGENCE to mi * 8.0 + mg)
            val post = converted.mapNotNull { (m, x) -> ladderIndex(x)?.let { Step(m, it) } }
            val bestPost = post.maxByOrNull { score(it.motive, it.idx) }
            if (before != null && bestPost != null && score(bestPost.motive, bestPost.idx) > score(before.motive, before.idx)) {
                val limit = Step(before.motive, minOf(before.idx + 1, 6))
                after = if (score(bestPost.motive, bestPost.idx) <= score(limit.motive, limit.idx)) bestPost else limit
                notes += "위법동기가 둘 이상(고의·중과실)이라 <표2>에 따라 환산 후 조치와 비교했고, 환산 전보다 1단계를 넘지 않도록 제한했습니다."
            }
        }
        val base = after
        val final = base?.let { Step(it.motive, (it.idx + netSteps).coerceIn(0, 6)) }
        val finalMultiple = (mult[Motive.INTENT] ?: 0.0) + (mult[Motive.GROSS_NEGLIGENCE] ?: 0.0) + (mult[Motive.NEGLIGENCE] ?: 0.0)
        // 과징금 중요도 산정용 규모배수: 고의+중과실 단순합산(<표2> 6.)
        val fineMult = mi + mg
        if (before == null && mult.isNotEmpty()) notes += "위반금액이 중요성 기준금액의 1배 미만이라 양정표(Ⅴ 이상) 대상이 아닙니다."
        return Assessment(mult, per, converted, before, after, base, final, netSteps, finalMultiple, fineMult, notes)
    }
}
