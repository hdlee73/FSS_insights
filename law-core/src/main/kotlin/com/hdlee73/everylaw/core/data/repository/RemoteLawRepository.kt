package com.hdlee73.everylaw.core.data.repository

import com.hdlee73.everylaw.core.data.remote.LawApiService
import com.hdlee73.everylaw.core.domain.LawRepository
import com.hdlee73.everylaw.core.model.AdminRuleDocument
import com.hdlee73.everylaw.core.model.AdminRuleSummary
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion
import com.hdlee73.everylaw.core.model.PrecedentDetail
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.model.ThreeTierComparison
import com.hdlee73.everylaw.core.parser.DrfParsers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 국가법령정보 DRF API 기반 저장소. 파싱은 IO 디스패처에서 수행한다. */
class RemoteLawRepository(
    private val api: LawApiService,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LawRepository {

    override val isSample: Boolean = false

    // 감독규정 본문은 수백 개 조문이라 같은 세션에서 다시 받지 않는다.
    private val adminRuleCache = ConcurrentHashMap<String, AdminRuleDocument>()
    // 판례 관련도 판정에 본문을 여러 건 받으므로, 판례 화면·PDF에서 다시 받지 않게 둔다.
    private val precedentCache = ConcurrentHashMap<String, PrecedentDetail>()

    override suspend fun searchLaws(query: String, bodySearch: Boolean): List<LawSummary> = withContext(io) {
        DrfParsers.parseLawSearch(api.searchLaws(query, search = if (bodySearch) 2 else 1))
    }

    override suspend fun getLawArticles(law: LawSummary, article: ArticleNo?): List<Article> = withContext(io) {
        DrfParsers.parseLawBody(api.getLaw(law.mst, article?.joParam)).articles
    }

    override suspend fun getLawVersions(law: LawSummary): List<LawVersion> = withContext(io) {
        val compact = law.name.replace(" ", "")
        DrfParsers.parseLawVersions(api.searchEffectiveLaws(law.name))
            .filter { it.name.replace(" ", "") == compact }
            .distinctBy { it.mst to it.effectiveDate }
            .sortedByDescending { it.effectiveDate }
    }

    override suspend fun searchLawVersionsAsOf(name: String, ymd: String): List<LawVersion> = withContext(io) {
        val compact = name.replace(" ", "")
        DrfParsers.parseLawVersions(api.searchEffectiveLaws(name))
            .filter { it.name.replace(" ", "").contains(compact) }
            .groupBy { it.name }
            .mapNotNull { (_, versions) -> versions.filter { it.effectiveDate <= ymd }.maxByOrNull { it.effectiveDate } }
            // 그 시점에 이미 폐지된 법령은 제외(가장 가까운 버전이 폐지 버전이면 폐지 후)
            .filter { it.revisionType?.contains("폐지") != true }
    }

    override suspend fun getLawArticlesAt(version: LawVersion, article: ArticleNo?): List<Article> = withContext(io) {
        DrfParsers.parseLawBody(api.getEffectiveLaw(version.mst, version.effectiveDate, article?.joParam)).articles
    }

    override suspend fun getThreeTier(law: LawSummary, mst: String): ThreeTierComparison = withContext(io) {
        DrfParsers.parseThreeTier(api.getThreeTierComparison(mst, kind = 2))
    }

    override suspend fun getRelatedAdminRuleNames(law: LawSummary): List<String> = withContext(io) {
        DrfParsers.parseHierarchyAdminRuleNames(api.getLawHierarchy(law.mst))
    }

    override suspend fun searchAdminRules(query: String): List<AdminRuleSummary> = withContext(io) {
        DrfParsers.parseAdminRuleSearch(api.searchAdminRules(query))
    }

    override suspend fun getAdminRule(serialNo: String): AdminRuleDocument = withContext(io) {
        adminRuleCache[serialNo] ?: DrfParsers.parseAdminRule(api.getAdminRule(serialNo)).also {
            adminRuleCache[serialNo] = it
        }
    }

    override suspend fun searchPrecedents(query: String, bodySearch: Boolean): List<PrecedentSummary> = withContext(io) {
        DrfParsers.parsePrecedentSearch(api.searchPrecedents(query, search = if (bodySearch) 2 else 1))
    }

    override suspend fun getPrecedent(serialNo: String): PrecedentDetail = withContext(io) {
        precedentCache[serialNo] ?: DrfParsers.parsePrecedent(api.getPrecedent(serialNo)).also { precedentCache[serialNo] = it }
    }
}
