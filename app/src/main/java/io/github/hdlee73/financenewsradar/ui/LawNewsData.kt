package io.github.hdlee73.financenewsradar.ui

import android.content.Context
import com.hdlee73.everylaw.core.domain.LawRepository
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion
import io.github.hdlee73.financenewsradar.law.LawContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

/** 오늘의 브리핑 '법령·하위 법규 개정 소식' 한 줄. [route]는 누르면 여는 웹 화면(법령 개정 내용). */
data class LawNewsEntry(
    val kind: String,
    val name: String,
    val type: String?,
    val promulgation: String?,
    val effective: String?,
    val route: String,
)

private val KEY_LAWS = listOf(
    "자본시장과 금융투자업에 관한 법률", "은행법", "보험업법", "금융소비자 보호에 관한 법률", "전자금융거래법", "여신전문금융업법",
    "가상자산 이용자 보호 등에 관한 법률", "상호저축은행법", "특정 금융거래정보의 보고 및 이용 등에 관한 법률", "금융위원회의 설치 등에 관한 법률",
    "금융지주회사법", "신용정보의 이용 및 보호에 관한 법률", "대부업 등의 등록 및 금융이용자 보호에 관한 법률", "예금자보호법",
    "주식회사 등의 외부감사에 관한 법률", "금융실명거래 및 비밀보장에 관한 법률", "한국은행법", "금융회사의 지배구조에 관한 법률",
    "채권의 공정한 추심에 관한 법률", "온라인투자연계금융업 및 이용자 보호에 관한 법률",
)

private val KEY_ADMIN_RULES = listOf(
    "금융투자업규정", "금융투자업규정 시행세칙", "증권 인수업무 등에 관한 규정", "보험업감독규정", "보험업감독업무시행세칙",
    "은행업감독규정", "은행업감독업무시행세칙", "여신전문금융업감독규정", "여신전문금융업감독업무시행세칙",
    "상호저축은행업감독규정", "상호저축은행업감독업무시행세칙", "금융소비자보호에 관한 감독규정", "전자금융감독규정",
    "금융지주회사감독규정", "신용정보업감독규정", "금융회사 지배구조 감독규정", "특정 금융거래정보의 보고 및 이용 등에 관한 감독규정",
    "가상자산 이용자 보호 등에 관한 감독규정", "주식회사 등의 외부감사에 관한 규정",
)

private const val CACHE_MS = 6 * 3600 * 1000L
private var cache: Pair<Long, List<LawNewsEntry>>? = null

private fun q(vararg p: Pair<String, String?>): String =
    p.filter { !it.second.isNullOrEmpty() }.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

private suspend fun <T> quiet(block: suspend () -> List<T>): List<T> =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

private suspend fun versionsOf(repo: LawRepository, name: String, per: Int, kind: String): List<LawNewsEntry> = quiet {
    val seen = HashSet<String>()
    repo.getLawVersions(LawSummary(id = "", mst = "", name = name, abbreviation = null, kind = null, effectiveDate = null))
        .sortedWith(compareByDescending<LawVersion> { it.promulgationDate.orEmpty() }.thenByDescending { it.effectiveDate })
        .filter { seen.add(it.promulgationDate.orEmpty() + it.revisionType.orEmpty()) }
        .take(per)
        .map {
            LawNewsEntry(
                kind, it.name, it.revisionType, it.promulgationDate, it.effectiveDate,
                "lawdoc?" + q("mst" to it.mst, "name" to it.name, "kind" to kind, "pd" to it.promulgationDate, "ef" to it.effectiveDate, "type" to it.revisionType)
            )
        }
}

private suspend fun adminOf(repo: LawRepository, name: String): List<LawNewsEntry> = quiet {
    val compact = name.replace(" ", "")
    val hit = repo.searchAdminRules(name).firstOrNull { it.name.replace(" ", "") == compact } ?: return@quiet emptyList()
    val kind = if (hit.name.contains("시행세칙")) "시행세칙" else "감독규정"
    listOf(
        LawNewsEntry(
            kind, hit.name, hit.revisionType, hit.promulgationDate, hit.effectiveDate,
            "lawdoc?" + q("adm" to hit.serialNo, "name" to hit.name, "kind" to kind, "pd" to hit.promulgationDate, "ef" to hit.effectiveDate, "type" to hit.revisionType)
        )
    )
}

/** 주요 금융 법령과 그 시행령·시행규칙·감독규정·시행세칙의 최신 개정 [limit]건(공포·발령일 최신순). 6시간 동안 메모리에 둔다. */
suspend fun loadLawNews(context: Context, limit: Int = 5): List<LawNewsEntry> {
    cache?.let { (t, v) -> if (v.isNotEmpty() && System.currentTimeMillis() - t < CACHE_MS) return v.take(limit) }
    val repo = LawContainer.get(context.applicationContext).repository
    val gate = Semaphore(6)
    val rows = coroutineScope {
        val jobs = ArrayList<Deferred<List<LawNewsEntry>>>()
        for (n in KEY_LAWS) {
            jobs += async { gate.withPermit { versionsOf(repo, n, 3, "법률") } }
            jobs += async { gate.withPermit { versionsOf(repo, "$n 시행령", 2, "시행령") } }
            jobs += async { gate.withPermit { versionsOf(repo, "$n 시행규칙", 1, "시행규칙") } }
        }
        for (n in KEY_ADMIN_RULES) jobs += async { gate.withPermit { adminOf(repo, n) } }
        jobs.awaitAll().flatten()
    }.sortedByDescending { it.promulgation.orEmpty() }
    if (rows.isNotEmpty()) cache = System.currentTimeMillis() to rows
    return rows.take(limit)
}
