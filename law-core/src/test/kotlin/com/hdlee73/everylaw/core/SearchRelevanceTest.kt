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

/** 본문 검색 결과가 가나다순으로 길게 나오고, 자본시장법은 맨 뒤(또는 아예 빠진) 상황 */
private class CrowdedRepo(private val cmaInBodyResults: Boolean) : LawRepository by FakeRepo() {
    private val cma = FakeRepo().law
    private val fillers = (1..9).map { LawSummary("f$it", "20$it", "가나다${it}법", null, "법률", "20260101") }
    override suspend fun searchLaws(query: String, bodySearch: Boolean): List<LawSummary> = when {
        bodySearch -> fillers + if (cmaInBodyResults) listOf(cma) else emptyList()
        CMA.replace(" ", "") == query.replace(" ", "") -> listOf(cma)
        else -> emptyList()
    }
    override suspend fun getLawArticles(law: LawSummary, article: ArticleNo?) =
        if (law.mst == cma.mst) FakeRepo().articles else listOf(Article(ArticleNo("1"), "(목적)", "제1조(목적) 관련 없음"))
}

class KeywordCrowdedTest {
    @Test fun majorLawIsScannedEvenWhenLastInResultList() = runTest {
        val r = SearchKeywordUseCase(CrowdedRepo(cmaInBodyResults = true))("손실보전")
        assertTrue(r.articleHits.any { it.law.name == CMA && it.article.number == ArticleNo("55") })
    }

    @Test fun widensToMajorFinancialLawsMissingFromResultList() = runTest {
        val r = SearchKeywordUseCase(CrowdedRepo(cmaInBodyResults = false))("손실보전")
        assertTrue(r.articleHits.any { it.law.name == CMA })
        assertTrue(r.scanNote.contains("넓혀"))
    }
}

/** 1999년에는 증권거래법만 있고 자본시장법(2009 시행)은 없던 상황 */
private class HistoricalRepo : LawRepository by FakeRepo() {
    private val oldName = "증권거래법"
    private val old = LawVersion("old1", oldName, "19980101", null, "일부개정", "연혁")
    private val audit = LawVersion("aud1", "주식회사의외부감사에관한법률", "19980101", null, "일부개정", "연혁")
    private val cma = LawVersion("cma1", CMA, "20090204", null, "제정", "현행")
    override suspend fun searchLaws(query: String, bodySearch: Boolean) = FakeRepo().let {
        if (bodySearch) listOf(it.law) else emptyList()
    }
    override suspend fun searchLawVersionsAsOf(name: String, ymd: String): List<LawVersion> =
        listOf(old, audit, cma).filter { it.name.replace(" ", "").contains(name.replace(" ", "")) && it.effectiveDate <= ymd }
    override suspend fun getLawArticlesAt(version: LawVersion, article: ArticleNo?) =
        if (version.mst == "aud1") listOf(Article(ArticleNo("2"), "(외부감사의 대상)", "제2조(외부감사의 대상) 외부감사를 받아야 한다."))
        else if (version.mst == "old1") listOf(Article(ArticleNo("8"), "(유가증권신고서)", "제8조(유가증권신고서) 증권신고서를 제출하여야 한다."))
        else FakeRepo().articles
}

class KeywordAsOfTest {
    @Test fun asOf1999FindsOldLawAndSkipsLawNotYetInForce() = runTest {
        val r = SearchKeywordUseCase(HistoricalRepo())("증권신고서", "19991006")
        assertTrue(r.articleHits.isNotEmpty())
        assertTrue(r.articleHits.all { it.law.name == "증권거래법" })
        assertTrue(r.laws.isEmpty() && r.adminRules.isEmpty())
        assertTrue(r.scanNote.contains("1999.10.06"))
    }

    @Test fun asOfFindsOldLawByKeywordInItsRenamedTitle() = runTest {
        val r = SearchKeywordUseCase(HistoricalRepo())("외부감사", "19991007")
        assertTrue(r.articleHits.any { it.law.name == "주식회사의외부감사에관한법률" })
        assertTrue(r.scanNote.contains("주식회사의외부감사에관한법률"))
    }

    @Test fun lawNamePlusKeywordAsOfUsesThatDatesVersion() = runTest {
        val r = SearchKeywordUseCase(HistoricalRepo())("증권거래법 증권신고서", "19991006")
        assertEquals("증권거래법", r.scopeLaw?.name)
        assertEquals(1, r.articleHits.size)
    }

    @Test fun lawNotYetInForceIsNotFoundAsOf() = runTest {
        val r = SearchKeywordUseCase(HistoricalRepo())("자본시장법 손실보전", "19991006")
        assertEquals(null, r.scopeLaw)
    }
}

class XmlTreeNonXmlTest {
    @org.junit.Test
    fun htmlResponseGivesReadableError() {
        val e = runCatching {
            com.hdlee73.everylaw.core.parser.XmlNode.parse("<!DOCTYPE html><html><head><meta charset=utf-8><title>접근 제한</title></head></html>")
        }.exceptionOrNull()
        org.junit.Assert.assertTrue(e?.message.orEmpty().contains("XML이 아닌 응답"))
    }
}
