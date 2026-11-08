package io.github.hdlee73.financenewsradar.accounting.domain

import io.github.hdlee73.financenewsradar.accounting.data.AuditAction
import kotlin.math.abs

data class CaseStats(val count: Int, val withFinalFine: Int, val prosecutionRatePct: Int, val medianDesignationYears: Int?)

object SimilarCases {
    /** 위반금액 최댓값이 입력 금액에 가까운 순으로 유사 사례를 고른다. market이 비어 있으면 전체. */
    fun find(items: List<AuditAction>, violationWon: Long, market: String = "", limit: Int = 5): List<AuditAction> =
        items.filter { it.maxFindingWon != null && (market.isEmpty() || it.market.contains(market)) }
            .sortedBy { abs(it.maxFindingWon!! - violationWon) }
            .take(limit)

    fun stats(cases: List<AuditAction>): CaseStats {
        if (cases.isEmpty()) return CaseStats(0, 0, 0, null)
        val years = cases.mapNotNull { it.flags.auditorDesignationYears }.sorted()
        return CaseStats(
            count = cases.size,
            withFinalFine = cases.count { it.flags.fineFinal },
            prosecutionRatePct = cases.count { it.flags.prosecution }.times(100) / cases.size,
            medianDesignationYears = years.getOrNull(years.size / 2)
        )
    }
}
