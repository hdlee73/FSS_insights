package com.hdlee73.everylaw.core

import com.hdlee73.everylaw.core.domain.GetDelegationTreeUseCase
import com.hdlee73.everylaw.core.domain.DelegationProgress
import com.hdlee73.everylaw.core.domain.LawRepository
import com.hdlee73.everylaw.core.domain.PrecedentRelevance
import com.hdlee73.everylaw.core.domain.SearchKeywordUseCase
import com.hdlee73.everylaw.core.model.AdminRuleDocument
import com.hdlee73.everylaw.core.model.AdminRuleSummary
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion
import com.hdlee73.everylaw.core.model.PrecedentDetail
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.model.ThreeTierComparison
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CMA = "자본시장과 금융투자업에 관한 법률"

class PrecedentRelevanceTest {
    private val names = listOf(CMA, "자본시장법")
    private val a55 = ArticleNo("55")

    @Test fun citesOwnLawArticle() {
        assertTrue(PrecedentRelevance.cites("구 자본시장과 금융투자업에 관한 법률(2013. 5. 28. 법률 제11845호로 개정되기 전의 것) 제55조, 제176조", names, a55))
        assertTrue(PrecedentRelevance.cites("자본시장법 제55조 위반", names, a55))
    }

    @Test fun ignoresSameNumberOfOtherLaw() {
        assertFalse(PrecedentRelevance.cites("구 증권거래법 제55조 / 자본시장법 제56조", names, a55))
        assertFalse(PrecedentRelevance.cites("자본시장법 시행령 제55조", names, a55))
        assertFalse(PrecedentRelevance.cites("자본시장법 제176조 / 민법 제55조", names, a55))
    }

    @Test fun branchNumbersAreExact() {
        assertFalse(PrecedentRelevance.cites("자본시장법 제55조의2", names, a55))
        assertTrue(PrecedentRelevance.cites("자본시장법 제55조의2", names, ArticleNo("55", 2)))
        assertFalse(PrecedentRelevance.cites("자본시장법 제55조", names, ArticleNo("55", 2)))
    }

    @Test fun ranksReferenceAboveBody() {
        fun d(ref: String, body: String) = PrecedentDetail(
            PrecedentSummary("1", "사건", null, null, null, null), "", "", ref, "", body,
        )
        assertEquals(PrecedentRelevance.Where.REFERENCE, PrecedentRelevance.where(d("자본시장법 제55조", ""), names, a55))
        assertEquals(PrecedentRelevance.Where.BODY, PrecedentRelevance.where(d("", "원심은 자본시장법 제55조를 적용했다"), names, a55))
        assertEquals(null, PrecedentRelevance.where(d("민법 제55조", "자본시장법 제56조"), names, a55))
    }
}

/** 조문 목록·판례만 가진 가짜 저장소 */
private class FakeRepo : LawRepository {
    override val isSample = false
    val law = LawSummary("1", "100", CMA, "자본시장법", "법률", "20260101")
    val articles = listOf(
        Article(ArticleNo("55"), "(손실보전 등의 금지)", "제55조(손실보전 등의 금지) 금융투자업자는 손실을 보전하여서는 아니 된다."),
        Article(ArticleNo("56"), "(투자권유)", "제56조(투자권유) 투자자의 손실보전을 약속하는 권유는 금지된다."),
        Article(ArticleNo("57"), "(기타)", "제57조(기타) 관련 없는 내용"),
    )
    override suspend fun searchLaws(query: String, bodySearch: Boolean) =
        if (CMA.replace(" ", "").contains(query.replace(" ", "")) || query == "손실보전") listOf(law) else emptyList()
    override suspend fun getLawArticles(law: LawSummary, article: ArticleNo?) =
        if (article == null) articles else articles.filter { it.number == article }
    override suspend fun getLawVersions(law: LawSummary) = emptyList<LawVersion>()
    override suspend fun getLawArticlesAt(version: LawVersion, article: ArticleNo?) = emptyList<Article>()
    override suspend fun getThreeTier(law: LawSummary, mst: String): ThreeTierComparison = throw UnsupportedOperationException()
    override suspend fun getRelatedAdminRuleNames(law: LawSummary) = emptyList<String>()
    override suspend fun searchAdminRules(query: String) = emptyList<AdminRuleSummary>()
    override suspend fun getAdminRule(serialNo: String): AdminRuleDocument = throw UnsupportedOperationException()
    val precs = mapOf(
        "p1" to ("구 자본시장법 제55조" to "2020"),
        "p2" to ("증권거래법 제55조" to "2021"),
        "p3" to ("자본시장법 제55조" to "2022"),
    )
    override suspend fun searchPrecedents(query: String, bodySearch: Boolean) =
        precs.map { (id, v) -> PrecedentSummary(id, "사건$id", "no$id", v.second + "0101", "대법원", "민사") }
    override suspend fun getPrecedent(serialNo: String): PrecedentDetail {
        val (ref, _) = precs.getValue(serialNo)
        return PrecedentDetail(searchPrecedents("").first { it.serialNo == serialNo }, "", "", ref, "", "")
    }
}

class KeywordScopeTest {
    @Test fun lawNamePlusKeywordFindsArticleByTitle() = runTest {
        val r = SearchKeywordUseCase(FakeRepo())("자본시장법 손실보전")
        assertEquals(CMA, r.scopeLaw?.name)
        assertEquals(listOf("제55조", "제56조"), r.articleHits.map { it.article.number.toString() })
        assertTrue(r.articleHits.first().inTitle)
        assertFalse(r.articleHits.last().inTitle)
    }

    @Test fun keywordAloneAlsoSearchesArticlesOfTopLaws() = runTest {
        val r = SearchKeywordUseCase(FakeRepo())("손실보전")
        assertEquals(null, r.scopeLaw)
        assertTrue(r.articleHits.any { it.article.number == ArticleNo("55") })
    }

    @Test fun unknownLawPrefixFallsBackToPlainSearch() = runTest {
        val r = SearchKeywordUseCase(FakeRepo())("없는법 손실보전")
        assertEquals(null, r.scopeLaw)
    }
}

class PrecedentFilterTest {
    @Test fun treeKeepsOnlyPrecedentsCitingThisLawArticle() = runTest {
        val repo = object : LawRepository by FakeRepo() {
            override suspend fun getThreeTier(law: LawSummary, mst: String): ThreeTierComparison = throw RuntimeException("no 3tier")
        }
        val done = GetDelegationTreeUseCase(repo)("자본시장법 제55조").toList().filterIsInstance<DelegationProgress.Done>().single()
        assertEquals(listOf("p3", "p1"), done.tree.precedents.map { it.serialNo })
        assertTrue(done.tree.precedents.all { it.matchNote == "참조조문에 인용" })
        assertTrue(done.tree.precedentBasis.contains("2건"))
    }
}

class ArticleCitationExtractorTest {
    private fun queries(s: String) = com.hdlee73.everylaw.core.parser.ArticleCitationExtractor.extract(s).map { it.query }

    @Test fun findsAliasAndFormalNames() {
        val text = "금융회사는 자본시장법 제55조 및 「금융소비자 보호에 관한 법률」 제19조제2항을 준수해야 한다."
        assertEquals(listOf("자본시장법 제55조", "금융소비자 보호에 관한 법률 제19조"), queries(text))
    }

    @Test fun keepsDecreeLevelAndBranchNumber() {
        assertEquals(listOf("자본시장법 시행령 제68조", "은행법 제34조의2"), queries("자본시장법 시행령 제68조, 은행법 제34조의2 참조"))
    }

    @Test fun skipsArticlesWithoutKnownLaw() {
        assertEquals(emptyList<String>(), queries("제5조에 따라 또는 민원 제3조 안내"))
    }

    @Test fun mergesDuplicatesAndCountsThem() {
        val r = com.hdlee73.everylaw.core.parser.ArticleCitationExtractor.extract("자본시장법 제55조 … 자본시장법 제55조")
        assertEquals(1, r.size)
        assertEquals(2, r[0].count)
    }

    @Test fun longerNameWinsOverSuffixMatch() {
        assertEquals(listOf("상호저축은행법 제10조"), queries("상호저축은행법 제10조"))
    }
}
