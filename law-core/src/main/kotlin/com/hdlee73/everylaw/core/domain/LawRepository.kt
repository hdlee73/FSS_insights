package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.AdminRuleDocument
import com.hdlee73.everylaw.core.model.AdminRuleSummary
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion
import com.hdlee73.everylaw.core.model.PrecedentDetail
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.model.ThreeTierComparison

/** 도메인이 데이터 출처(API/샘플)를 모른 채 쓰는 저장소 계약. */
interface LawRepository {
    /** 데모(샘플) 데이터인지. API 인증키가 없으면 true. */
    val isSample: Boolean

    suspend fun searchLaws(query: String, bodySearch: Boolean = false): List<LawSummary>
    suspend fun getLawArticles(law: LawSummary, article: ArticleNo? = null): List<Article>
    /** 시행일 기준 버전 목록(최신 시행일 먼저) */
    suspend fun getLawVersions(law: LawSummary): List<LawVersion>
    /** 특정 시행일 버전의 조문 */
    suspend fun getLawArticlesAt(version: LawVersion, article: ArticleNo? = null): List<Article>
    /** 3단비교. [mst]를 주면 해당 버전 기준으로 요청한다. */
    suspend fun getThreeTier(law: LawSummary, mst: String = law.mst): ThreeTierComparison
    /** 법령 체계도에서 찾은 관련 행정규칙 이름 (실패 시 빈 목록) */
    suspend fun getRelatedAdminRuleNames(law: LawSummary): List<String>
    suspend fun searchAdminRules(query: String): List<AdminRuleSummary>
    suspend fun getAdminRule(serialNo: String): AdminRuleDocument
    suspend fun searchPrecedents(query: String, bodySearch: Boolean = true): List<PrecedentSummary>
    suspend fun getPrecedent(serialNo: String): PrecedentDetail
}
