package com.hdlee73.everylaw.core.data.repository

import com.hdlee73.everylaw.core.domain.LawRepository
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion

/**
 * 인증키(OC)가 있으면 [remote], 없으면 [sample]을 쓴다.
 * 설정에서 키를 바꾸면 앱 재시작 없이 바로 반영된다.
 */
class SwitchingLawRepository(
    private val hasKey: () -> Boolean,
    private val remote: LawRepository,
    private val sample: LawRepository = SampleLawRepository(),
) : LawRepository {
    private val current: LawRepository get() = if (hasKey()) remote else sample

    override val isSample: Boolean get() = current.isSample
    override suspend fun searchLaws(query: String, bodySearch: Boolean) = current.searchLaws(query, bodySearch)
    override suspend fun getLawArticles(law: LawSummary, article: ArticleNo?) = current.getLawArticles(law, article)
    override suspend fun getLawVersions(law: LawSummary) = current.getLawVersions(law)
    override suspend fun getLawArticlesAt(version: LawVersion, article: ArticleNo?) = current.getLawArticlesAt(version, article)
    override suspend fun getThreeTier(law: LawSummary, mst: String) = current.getThreeTier(law, mst)
    override suspend fun getRelatedAdminRuleNames(law: LawSummary) = current.getRelatedAdminRuleNames(law)
    override suspend fun searchAdminRules(query: String) = current.searchAdminRules(query)
    override suspend fun getAdminRule(serialNo: String) = current.getAdminRule(serialNo)
    override suspend fun searchPrecedents(query: String, bodySearch: Boolean) = current.searchPrecedents(query, bodySearch)
    override suspend fun getPrecedent(serialNo: String) = current.getPrecedent(serialNo)
}
