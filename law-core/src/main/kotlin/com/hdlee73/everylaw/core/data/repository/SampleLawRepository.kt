package com.hdlee73.everylaw.core.data.repository

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
import com.hdlee73.everylaw.core.model.ThreeTierRow
import kotlinx.coroutines.delay

/**
 * 인증키(OC)가 없을 때 화면 흐름을 체험하는 데모 저장소.
 * 본문은 실제 조문을 요약·가공한 "예시"이며 법적 효력이 있는 원문이 아니다. 화면에 데모 배지가 붙는다.
 */
class SampleLawRepository(private val latencyMs: Long = 350) : LawRepository {

    override val isSample: Boolean = true

    private val law = LawSummary(
        id = "SAMPLE", mst = "SAMPLE", name = "자본시장과 금융투자업에 관한 법률",
        abbreviation = "자본시장법", kind = "법률", effectiveDate = null,
    )

    private fun a(no: String, title: String, body: String) = Article(ArticleNo.parse(no), title, "[예시] $body")

    override suspend fun searchLaws(query: String, bodySearch: Boolean): List<LawSummary> {
        delay(latencyMs)
        return listOf(law)
    }

    override suspend fun getLawArticles(law: LawSummary, article: ArticleNo?): List<Article> {
        delay(latencyMs)
        return listOf(
            a("제11조", "무인가 영업행위 금지", "누구든지 이 법에 따른 금융투자업인가를 받지 아니하고는 금융투자업을 영위해서는 아니 된다."),
            a("제12조", "금융투자업의 인가", "① 금융투자업을 영위하려는 자는 인가업무 단위의 전부나 일부를 선택하여 금융위원회로부터 하나의 금융투자업인가를 받아야 한다.\n② 인가를 받으려는 자는 대통령령으로 정하는 요건을 갖추어야 한다."),
        ).filter { article == null || it.number == article }
    }

    override suspend fun getLawVersions(law: LawSummary): List<LawVersion> {
        delay(latencyMs)
        return listOf(
            LawVersion("SAMPLE", law.name, "20270101", "20260701", "일부개정", "시행예정"),
            LawVersion("SAMPLE", law.name, "20260101", "20250701", "일부개정", "현행"),
            LawVersion("SAMPLE-OLD", law.name, "20240101", "20230701", "일부개정", "연혁"),
        )
    }

    override suspend fun getLawArticlesAt(version: LawVersion, article: ArticleNo?): List<Article> =
        getLawArticles(law, article).map {
            if (version.status == "연혁") it.copy(content = it.content + "\n(예시: ${version.effectiveDateLabel} 시행 당시 조문)") else it
        }

    override suspend fun getThreeTier(law: LawSummary, mst: String): ThreeTierComparison {
        delay(latencyMs)
        val lawArticles = getLawArticles(law)
        return ThreeTierComparison(
            lawName = law.name,
            decreeName = "${law.name} 시행령",
            ruleName = "${law.name} 시행규칙",
            rows = listOf(
                ThreeTierRow(
                    law = lawArticles[0],
                    decrees = listOf(a("제14조", "인가 대상 금융투자업의 범위", "법 제11조에 따른 금융투자업의 범위는 별표와 같다.")),
                    rules = emptyList(),
                ),
                ThreeTierRow(
                    law = lawArticles[1],
                    decrees = listOf(a("제16조", "인가요건 등", "① 법 제12조제2항제1호에서 \"대통령령으로 정하는 금융기관\"이란 다음 각 호의 자를 말한다.\n② 대주주의 요건에 관한 세부 기준은 금융위원회가 정하여 고시한다.")),
                    rules = listOf(a("제2조", "인가신청서의 기재사항", "영 제16조에 따른 인가신청서에는 다음 사항을 적어야 한다.")),
                ),
            ),
        )
    }

    override suspend fun getRelatedAdminRuleNames(law: LawSummary): List<String> =
        listOf("금융투자업규정", "금융투자업규정시행세칙")

    override suspend fun searchAdminRules(query: String): List<AdminRuleSummary> {
        delay(latencyMs)
        return listOf(
            AdminRuleSummary("SUP", "금융투자업규정", "고시", "금융위원회"),
            AdminRuleSummary("DET", "금융투자업규정시행세칙", "세칙", "금융감독원"),
        ).filter { query.isBlank() || it.name.contains(query) || query.contains(it.name) || query.length <= 4 }
    }

    override suspend fun getAdminRule(serialNo: String): AdminRuleDocument {
        delay(latencyMs)
        return if (serialNo == "SUP") AdminRuleDocument(
            serialNo, "금융투자업규정", "금융위원회",
            listOf(
                a("제2-3조", "대주주의 요건", "제2-3조(대주주의 요건) ① 영 제16조에 따른 대주주의 요건은 다음 각 호와 같다.\n1. 최근 일정 기간 금융관련법령 위반 사실이 없을 것\n2. 재무건전성 기준을 충족할 것\n② 세부 기준은 금융감독원장이 정한다."),
                a("제2-4조", "인가신청서의 서식", "제2-4조(인가신청서의 서식) 규칙 제2조에 따른 인가신청서는 별지 서식에 따른다."),
            ),
        ) else AdminRuleDocument(
            serialNo, "금융투자업규정시행세칙", "금융감독원",
            listOf(a("제3조", "대주주 요건의 세부 기준", "제3조(대주주 요건의 세부 기준) 규정 제2-3조제2항에 따른 세부 기준은 별표 1과 같다.")),
        )
    }

    override suspend fun searchPrecedents(query: String, bodySearch: Boolean): List<PrecedentSummary> {
        delay(latencyMs)
        return listOf(
            PrecedentSummary("P1", "[예시] 자본시장과금융투자업에관한법률위반", "20XX도0000", null, "대법원", "형사"),
            PrecedentSummary("P2", "[예시] 금융투자업 인가취소처분 취소", "20XX두0000", null, "서울행정법원", "일반행정"),
        )
    }

    override suspend fun getPrecedent(serialNo: String): PrecedentDetail {
        delay(latencyMs)
        val s = searchPrecedents("").first { it.serialNo == serialNo }
        return PrecedentDetail(
            summary = s,
            holdings = "[예시] 인가를 받지 아니한 채 금융투자업을 영위한 경우의 판단 기준",
            gist = "[예시] 데모 모드에서는 실제 판례 대신 화면 구성을 보여주는 예시 문장이 표시됩니다. 설정에서 국가법령정보 OC를 입력하면 실제 판례가 조회됩니다.",
            referencedArticles = "자본시장과 금융투자업에 관한 법률 제11조, 제12조",
            referencedCases = "",
            fullText = "",
        )
    }
}
