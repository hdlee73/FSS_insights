package io.github.hdlee73.financenewsradar.accounting.domain

/** 양정 대상자 종류. 회사·임직원은 회사 조치표, 감사인은 감사인 조치표, 공인회계사는 공인회계사 조치표를 쓴다. */
enum class ActorKind(val label: String, val companySide: Boolean) {
    COMPANY("회사", true), OFFICER("임직원", true), AUDITOR("감사인", false), CPA("공인회계사", false)
}

/** 양정 대상자 한 명(회사, 임직원 각자, 감사인, 공인회계사 각자). 가중·감경 사유와 과징금 요소를 대상자마다 따로 둔다. */
data class Actor(
    val id: Int,
    val kind: ActorKind,
    val name: String = "",
    val reasons: Set<String> = emptySet(),
    /** 위법행위 내용 중요도(과징금 점수용) */
    val content: Level = if (kind.companySide) Level.MID else Level.LOW,
    /** 임직원: 경제적 이익(억원) */
    val benefitEok: String = "",
    /** 감사인: 감사보수(억원) */
    val feeEok: String = "",
    val upFreePct: Int = 0,
    val downFreePct: Int = 0
) {
    val title: String get() = name.ifBlank { kind.label }
}

const val COMPANY_ID = 1

fun defaultMotive(k: ActorKind): Motive = if (k.companySide) Motive.INTENT else Motive.GROSS_NEGLIGENCE

fun defaultActors(): List<Actor> = listOf(
    Actor(COMPANY_ID, ActorKind.COMPANY, "회사"),
    Actor(4, ActorKind.OFFICER, "임직원 1"),
    Actor(2, ActorKind.AUDITOR, "감사인"),
    Actor(3, ActorKind.CPA, "공인회계사 1")
)

fun defaultAssign(actors: List<Actor>): Map<Int, Motive> = actors.associate { it.id to defaultMotive(it.kind) }

data class ViolationRow(
    val id: Int,
    val type: VType = VType.A,
    val basis: ScaleBasis = VType.A.defaultBasis,
    val amountEok: String = "",
    /** 대상자 id → 그 대상자의 위법 동기. 없으면 그 대상자에게 해당하지 않는 위반행위 */
    val assign: Map<Int, Motive> = defaultAssign(defaultActors()),
    /** 위반행위 제목(예: 매출 과대계상) */
    val title: String = ""
)

data class SimSettings(
    val kind: CompanyKind = CompanyKind.KOSDAQ,
    val financial: Boolean = false,
    val halfCoefficient: Boolean = false,
    val assetEok: String = "",
    val salesEok: String = "",
    val materialityEok: String = "",
    val rows: List<ViolationRow> = listOf(ViolationRow(1)),
    val actors: List<Actor> = defaultActors(),
    val marketImpact: Level = Level.LOW,
    val years: Int = 1,
    val capmEok: String = ""
)

data class FineBlock(
    val title: String,
    val scoreLines: List<String>,
    val result: FineResult?,
    val note: String
)

typealias FineResult = FineModel.FineResult

/** 대상자 한 명의 계산 결과 */
data class ActorResult(
    val actor: Actor,
    val rowCount: Int,
    val assessment: Yangjeong.Assessment?,
    val actions: List<String>,
    val fine: FineBlock?,
    val note: String = ""
)

data class SimResult(
    val error: String?,
    val scaleLines: List<String> = emptyList(),
    val actors: List<ActorResult> = emptyList(),
    val notes: List<String> = emptyList()
)

object Simulation {
    private fun fineAdjUp(sel: Set<String>, list: List<Reason>, auto64: Boolean) =
        list.filter { it.id in sel || (auto64 && it.id.endsWith("_64")) }.map { it.fine }.toSet()
    private fun fineAdjDown(sel: Set<String>, list: List<Reason>) = list.filter { it.id in sel }.map { it.fine }.toSet()

    private class Valid(val row: ViolationRow, val won: Long)

    fun run(s: SimSettings): SimResult {
        val assetWon = Format.eokToWon(s.assetEok) ?: 0L
        val salesWon = Format.eokToWon(s.salesEok) ?: 0L
        val matWon = Format.eokToWon(s.materialityEok)?.takeIf { it > 0 }
        val valid = s.rows.mapNotNull { r -> Format.eokToWon(r.amountEok)?.takeIf { it > 0 }?.let { Valid(r, it) } }
        if (valid.isEmpty()) return SimResult("위법행위 금액을 1건 이상 입력하세요 (억원).")
        if (matWon == null && (assetWon <= 0 || (salesWon <= 0 && valid.any { it.row.basis != ScaleBasis.ASSET }))) {
            return SimResult("자산총계와 매출액(억원)을 입력하거나, 감사인의 중요성 금액을 입력하세요.")
        }
        val scale = CompanyScale(assetWon, salesWon, s.kind, s.financial, s.halfCoefficient)
        val scaleLines = mutableListOf<String>()
        if (matWon != null) scaleLines += "감사인 중요성 금액 ${Format.won(matWon)} 적용 (유형 배수 A 1배·B 4배·C 5배·D 15배)"
        else {
            for (b in ScaleBasis.entries) {
                if (valid.none { it.row.basis == b }) continue
                val amt = Yangjeong.scaleAmountWon(scale, b)
                scaleLines += "규모금액(${b.label} 기준) ${Format.won(amt)} → 표준 중요성 기준금액 A ${Format.won(amt / 100)} · B ${Format.won(amt * 4 / 100)} · C ${Format.won(amt * 5 / 100)} · D ${Format.won(amt * 15 / 100)}"
            }
        }
        val capmWon = Format.eokToWon(s.capmEok)?.takeIf { it > 0 }
        val results = s.actors.sortedBy { it.kind.ordinal }.map { runActor(s, it, valid, scale, matWon, capmWon) }
        val notes = listOf("고의·중과실이 혼재하면 과징금 중요도는 두 동기의 규모배수를 단순합산(<표2> 6.)해 위반 규모 항목을 판단합니다.")
        return SimResult(null, scaleLines, results, notes)
    }

    private fun runActor(s: SimSettings, a: Actor, valid: List<Valid>, scale: CompanyScale, matWon: Long?, capmWon: Long?): ActorResult {
        val items = valid.mapNotNull { v -> v.row.assign[a.id]?.let { m -> ViolationItem(v.row.type, v.row.basis, v.won, m) } }
        if (items.isEmpty()) return ActorResult(a, 0, null, emptyList(), null, "배정된 위반행위가 없습니다. 위반행위마다 이 대상자의 위법 동기를 고르세요.")
        val up = if (a.kind.companySide) Reasons.companyUp else Reasons.auditorUp
        val down = if (a.kind.companySide) Reasons.companyDown else Reasons.auditorDown
        val first = Yangjeong.assess(scale, items, matWon, 0)
        val auto64 = first.finalMultiple >= 64
        val net = Reasons.netSteps(a.reasons, up, down, auto64, first.base?.motive)
        val asm = Yangjeong.assess(scale, items, matWon, net)
        val table = when (a.kind) {
            ActorKind.COMPANY, ActorKind.OFFICER -> ActionTables.company
            ActorKind.AUDITOR -> ActionTables.auditor
            ActorKind.CPA -> ActionTables.cpa
        }
        val actions = asm.final?.let { ActionTables.lookup(table, it) } ?: emptyList()
        if (a.kind == ActorKind.CPA) return ActorResult(a, items.size, asm, actions, null)

        val fineItems = items.filter { it.motive != Motive.NEGLIGENCE }
        if (fineItems.isEmpty()) return ActorResult(a, items.size, asm, actions, null, "모든 위법행위가 '과실'이면 외감법상 과징금 대상이 아닙니다(경고·주의·증권발행제한 등 조치).")
        val total = fineItems.sumOf { it.amountWon }
        val groups = listOf(Motive.INTENT, Motive.GROSS_NEGLIGENCE).mapNotNull { m ->
            val g = fineItems.filter { it.motive == m }
            if (g.isEmpty()) null else m to g.sumOf { it.amountWon }
        }
        val sizeLevel = FineModel.sizeLevel(asm.fineMultiple)
        val kindLevel = FineModel.kindLevel(s.kind)
        val upIds = fineAdjUp(a.reasons, up, auto64)
        val downIds = fineAdjDown(a.reasons, down)
        val compMotive = if (groups.any { it.first == Motive.INTENT }) Motive.INTENT else Motive.GROSS_NEGLIGENCE
        val contentHigh = a.content == Level.HIGH && compMotive != Motive.GROSS_NEGLIGENCE

        val block: FineBlock = when (a.kind) {
            ActorKind.COMPANY, ActorKind.OFFICER -> {
                var basic = 0.0
                var weighted = 0.0
                val scoreLines = mutableListOf<String>()
                for ((m, amt) in groups) {
                    val sc = FineModel.score(a.content, sizeLevel, kindLevel, s.marketImpact, m)
                    val (cr, _) = FineModel.rate(sc.score)
                    basic += amt * cr / 100.0
                    weighted += sc.score * amt
                    scoreLines += "[${m.label}] 중요도 점수 ${sc.score} → 회사 부과기준율 ${cr}% (기준금액 ${Format.won(amt)})"
                    scoreLines += sc.lines.map { "   $it" }
                }
                val sumBase = groups.sumOf { it.second }
                val cap = total * 20 / 100
                if (a.kind == ActorKind.COMPANY) {
                    val res = FineModel.compute(FineModel.FineInputs(
                        "회사", sumBase, basic.toLong(), "Σ(동기별 기준금액 × 부과기준율)", compMotive, upIds, a.upFreePct, s.years, contentHigh, true,
                        downIds, a.downFreePct, cap, capmWon
                    ))
                    FineBlock("${a.title} 과징금", scoreLines, res, "법정 상한(위반금액의 20%) ${Format.won(cap)}")
                } else {
                    val avgScore = weighted / sumBase
                    val benefit = Format.eokToWon(a.benefitEok)?.takeIf { it > 0 }
                    val officerBase = maxOf(benefit ?: 0L, 100_000_000L)
                    val oRate = FineModel.rate(avgScore).second
                    val officerCap = cap / 10
                    val res = FineModel.compute(FineModel.FineInputs(
                        "회사 관계자", officerBase, (officerBase * oRate / 100.0).toLong(),
                        "받은 경제적 이익 ${Format.won(officerBase)} × ${oRate.toLong()}% (위반별 점수의 가중평균 ${"%.2f".format(avgScore)})",
                        compMotive, upIds, a.upFreePct, s.years, contentHigh, false, downIds, a.downFreePct, officerCap, null
                    ))
                    FineBlock("${a.title} 과징금", emptyList(), res,
                        "기준금액: 보수·배당 등 일체의 경제적 이익(산출 곤란하거나 1억원 이하이면 1억원). 법정 상한(위반금액 20%의 10%) ${Format.won(officerCap)}")
                }
            }
            else -> {
                val fee = Format.eokToWon(a.feeEok)?.takeIf { it > 0 }
                if (fee == null) FineBlock("${a.title} 과징금", emptyList(), null, "감사보수(억원)를 입력하면 계산합니다.")
                else {
                    val sc = FineModel.score(a.content, sizeLevel, kindLevel, s.marketImpact, compMotive)
                    val rate = FineModel.rate(sc.score).second
                    val res = FineModel.compute(FineModel.FineInputs(
                        "감사인", fee, (fee * rate / 100.0).toLong(), "감사보수 ${Format.won(fee)} × 부과기준율 ${rate.toLong()}% (점수 ${sc.score})",
                        compMotive, upIds, a.upFreePct, s.years, contentHigh, false, downIds, a.downFreePct, fee * 5, capmWon
                    ))
                    FineBlock("${a.title} 과징금", listOf("중요도 점수 ${sc.score}") + sc.lines.map { "   $it" }, res, "법정 상한(감사보수의 5배) ${Format.won(fee * 5)}")
                }
            }
        }
        return ActorResult(a, items.size, asm, actions, block)
    }
}
