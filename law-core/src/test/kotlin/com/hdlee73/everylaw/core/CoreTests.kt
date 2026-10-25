package com.hdlee73.everylaw.core

import com.hdlee73.everylaw.core.data.repository.SampleLawRepository
import com.hdlee73.everylaw.core.domain.DelegationProgress
import com.hdlee73.everylaw.core.domain.DelegationTreeBuilder
import com.hdlee73.everylaw.core.domain.GetDelegationTreeUseCase
import com.hdlee73.everylaw.core.domain.SearchKeywordUseCase
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.parser.DrfParsers
import com.hdlee73.everylaw.core.parser.LawQueryParser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LawQueryParserTest {
    private fun ok(s: String) = (LawQueryParser.parse(s) as LawQueryParser.Result.Ok).query

    @Test fun parsesAbbreviationAndArticle() {
        val q = ok("자본시장법 제11조")
        assertEquals("자본시장과 금융투자업에 관한 법률", q.lawName)
        assertEquals(ArticleNo("11"), q.article)
    }

    @Test fun parsesBranchAndLooseForms() {
        assertEquals(ArticleNo("34", 2), ok("은행법 34조의2").article)
        assertEquals(ArticleNo("12"), ok("자본시장법 §12").article)
        assertEquals("은행법", ok("은행법제34조").lawName)
    }

    @Test fun rejectsMissingParts() {
        assertTrue(LawQueryParser.parse("자본시장법") is LawQueryParser.Result.Invalid)
        assertTrue(LawQueryParser.parse("제11조") is LawQueryParser.Result.Invalid)
    }

    @Test fun joParam() {
        assertEquals("001200", ArticleNo("12").joParam)
        assertEquals("003402", ArticleNo("34", 2).joParam)
    }
}

class ReferenceMatchTest {
    @Test fun matchesExactArticleOnly() {
        val no = ArticleNo("12")
        assertTrue(DelegationTreeBuilder.refersTo("법 제12조제2항에 따라", DelegationTreeBuilder.LAW_PREFIX, no))
        assertFalse(DelegationTreeBuilder.refersTo("법 제120조에 따라", DelegationTreeBuilder.LAW_PREFIX, no))
        assertFalse(DelegationTreeBuilder.refersTo("법 제12조의2에 따라", DelegationTreeBuilder.LAW_PREFIX, no))
        assertFalse(DelegationTreeBuilder.refersTo("은행법 제12조", DelegationTreeBuilder.LAW_PREFIX, no))
        assertTrue(DelegationTreeBuilder.refersTo("영 제16조에 따른", DelegationTreeBuilder.DECREE_PREFIX, ArticleNo("16")))
        assertTrue(DelegationTreeBuilder.refersTo("규정 제2-3조제2항", DelegationTreeBuilder.SUPERVISORY_PREFIX, ArticleNo("2-3")))
        assertFalse(DelegationTreeBuilder.refersTo("규정 제2-30조", DelegationTreeBuilder.SUPERVISORY_PREFIX, ArticleNo("2-3")))
    }
}

class DrfParsersTest {
    @Test fun parsesLawSearch() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <LawSearch><target>law</target><totalCnt>1</totalCnt>
            <law id="1"><법령일련번호>123456</법령일련번호><법령명한글><![CDATA[자본시장과 금융투자업에 관한 법률]]></법령명한글>
            <법령약칭명><![CDATA[자본시장법]]></법령약칭명><법령ID>009999</법령ID><법령구분명>법률</법령구분명><시행일자>20260101</시행일자></law>
            </LawSearch>"""
        val r = DrfParsers.parseLawSearch(xml).single()
        assertEquals("123456", r.mst)
        assertEquals("자본시장법", r.abbreviation)
    }

    @Test fun parsesKoreanTagsIncludingKeyword() {
        val xml = """<LawSearch><totalCnt>1</totalCnt><키워드>a&amp;b</키워드><키워드목록 구분="1">x</키워드목록><law><법령명한글>가나다</법령명한글></law></LawSearch>"""
        val root = com.hdlee73.everylaw.core.parser.XmlNode.parse(xml)
        assertEquals("a&b", root.firstText("키워드"))
        assertEquals("x", root.firstText("키워드목록"))
        assertEquals("1", root.first("키워드목록")!!.attributes["구분"])
        assertEquals("가나다", DrfParsers.parseLawSearch(xml).single().name)
    }

    @Test fun emptyListWithTotalCntIsNotAnError() {
        val xml = "<LawSearch><target>law</target><totalCnt>0</totalCnt></LawSearch>"
        assertTrue(DrfParsers.parseLawSearch(xml).isEmpty())
    }

    @Test fun unexpectedEmptyResponseReportsRootAndSnippet() {
        val xml = "<error><msg>사용자 정보 검증에 실패하였습니다.</msg></error>"
        val e = runCatching { DrfParsers.parseLawSearch(xml) }.exceptionOrNull()
        assertTrue(e is com.hdlee73.everylaw.core.parser.DrfFormatException)
        assertTrue(e!!.message!!.contains("<error>") && e.message!!.contains("검증에 실패"))
    }

    @Test fun parsesThreeTierRows() {
        val xml = """<ThdCmpLawXService><기본정보><법령명>테스트법</법령명><시행령명>테스트법 시행령</시행령명></기본정보>
            <위임조문삼단비교>
              <법률조문><조번호>12</조번호><조제목>인가</조제목><조내용>제12조(인가) ② 대통령령으로 정하는 요건</조내용></법률조문>
              <시행령조문><조번호>16</조번호><조제목>인가요건</조제목><조내용>제16조(인가요건) 법 제12조</조내용></시행령조문>
              <시행규칙조문><조번호>2</조번호><조내용>제2조(신청서) 영 제16조</조내용></시행규칙조문>
            </위임조문삼단비교></ThdCmpLawXService>"""
        val t = DrfParsers.parseThreeTier(xml)
        assertEquals("테스트법 시행령", t.decreeName)
        val row = t.rows.single()
        assertEquals(ArticleNo("12"), row.law.number)
        assertEquals("인가요건", row.decrees.single().title)
        assertEquals("신청서", row.rules.single().title)
    }

    @Test fun parsesLawBodyWithParagraphs() {
        val xml = """<법령><기본정보><법령명_한글>테스트법</법령명_한글></기본정보><조문>
            <조문단위><조문번호>1</조문번호><조문여부>전문</조문여부><조문내용>제1장 총칙</조문내용></조문단위>
            <조문단위><조문번호>12</조문번호><조문여부>조문</조문여부><조문제목>인가</조문제목><조문내용>제12조(인가)</조문내용>
              <항><항내용>① 첫째 항</항내용></항><항><항내용>② 둘째 항</항내용><호><호내용>1. 호</호내용></호></항></조문단위>
            </조문></법령>"""
        val body = DrfParsers.parseLawBody(xml)
        val art = body.articles.single()
        assertEquals("인가", art.title)
        assertTrue(art.content.contains("② 둘째 항\n1. 호"))
    }

    @Test fun splitsAdminRuleArticles() {
        val arts = DrfParsers.splitAdminArticles("제2장 인가\n제2-3조(대주주의 요건) ① 영 제16조\n제2-4조(서식) 규칙 제2조")
        assertEquals(listOf("2-3", "2-4"), arts.map { it.number?.main })
        assertEquals("대주주의 요건", arts[0].title)
    }

    @Test fun parsesPrecedent() {
        val xml = """<PrecService><판례정보일련번호>1</판례정보일련번호><사건명>사건</사건명><판결요지>요지<br/>둘째줄</판결요지><참조조문>법 제12조</참조조문></PrecService>"""
        val p = DrfParsers.parsePrecedent(xml)
        assertEquals("사건", p.summary.caseName)
        assertEquals("법 제12조", p.referencedArticles)
    }

    @Test(expected = com.hdlee73.everylaw.core.parser.DrfFormatException::class)
    fun rejectsHtmlError() {
        DrfParsers.parseLawSearch("<!DOCTYPE html><html><body>사용자 정보 검증에 실패하였습니다.</body></html>")
    }
}

class UseCaseTest {
    @Test fun buildsFiveLevelTreeFromSample() = runTest {
        val progress = GetDelegationTreeUseCase(SampleLawRepository(latencyMs = 0))("자본시장법 제12조").toList()
        val tree = (progress.last() as DelegationProgress.Done).tree
        val levels = tree.root.flatten().map { it.level }.toSet()
        assertEquals(LawLevel.entries.toSet(), levels)
        val det = tree.root.flatten().first { it.level == LawLevel.ENFORCEMENT_DETAIL }
        assertEquals(
            listOf(LawLevel.LAW, LawLevel.DECREE, LawLevel.SUPERVISORY_REGULATION, LawLevel.ENFORCEMENT_DETAIL),
            tree.root.pathTo(det.id).map { it.level },
        )
        assertTrue(tree.precedents.isNotEmpty())
        assertTrue(tree.isSample)
    }

    @Test fun keywordSearchReturnsAllSections() = runTest {
        val r = SearchKeywordUseCase(SampleLawRepository(latencyMs = 0))("금융투자")
        assertTrue(r.laws.isNotEmpty())
        assertTrue(r.precedents.isNotEmpty())
    }
}

class AsOfTest {
    @Test fun picksVersionInForceOnDate() = runTest {
        val progress = GetDelegationTreeUseCase(SampleLawRepository(latencyMs = 0))("자본시장법 제12조", asOf = "20250315").toList()
        val tree = (progress.last() as DelegationProgress.Done).tree
        assertEquals("20240101", tree.version?.effectiveDate)
        assertTrue(tree.root.article.content.contains("2024.01.01 시행 당시"))
    }
}

class CitationLinkerTest {
    private val ctx = com.hdlee73.everylaw.core.parser.CitationLinker.Context("자본시장법", "자본시장법 시행령", "자본시장법 시행규칙")
    private fun queries(text: String, c: com.hdlee73.everylaw.core.parser.CitationLinker.Context = ctx) =
        com.hdlee73.everylaw.core.parser.CitationLinker.find(text, c).map { it.query }

    @Test fun bareAndBranchCitationsUseOwnLaw() {
        assertEquals(listOf("자본시장법 제12조", "자본시장법 제16조의2", "자본시장법 제18조"),
            queries("제12조에 따른 인가, 제16조의2에 따른 등록 또는 제18조"))
    }

    @Test fun prefixesPickTheRightLaw() {
        assertEquals(listOf("자본시장법 제11조", "자본시장법 시행령 제16조", "자본시장법 시행규칙 제2조"),
            queries("법 제11조, 영 제16조 및 시행규칙 제2조"))
    }

    @Test fun bracketedOtherLawKeepsItsName() {
        assertEquals(listOf("금융소비자 보호에 관한 법률 제51조"), queries("「금융소비자 보호에 관한 법률」 제51조제1항제4호"))
    }

    @Test fun unbracketedOtherLawAndAdminRules() {
        assertEquals(listOf("은행법 제34조"), queries("은행법 제34조"))
        val sup = ctx.copy(ownName = null)
        assertEquals(listOf("자본시장법 제11조"), queries("제2-3조와 법 제11조", sup).let { it })
    }

    @Test fun leadingZeroNumbersMatchCitations() {
        val no = ArticleNo.of("0420")
        assertEquals("420", no.main)
        assertTrue(DelegationTreeBuilder.refersTo("법 제420조제1항", DelegationTreeBuilder.LAW_PREFIX, no))
    }
}

class ArticleItemsTest {
    private val sample = """제420조(금융투자업자에 대한 조치) ① 금융위원회는 다음 각 호의 어느 하나에 해당하는 경우 취소할 수 있다.
1. 거짓으로 인가를 받은 경우
2. 대통령령으로 정하는 경우
가. 갑 사유
나. 을 사유
② 금융투자업자는 이를 이행하여야 한다.
이어지는 설명
③ 기타 사항은 대통령령으로 정한다."""

    @Test fun splitsParagraphsSubparagraphsAndItems() {
        val p = com.hdlee73.everylaw.core.parser.ArticleItems.parse(sample)
        assertEquals("제420조(금융투자업자에 대한 조치)", p.header)
        assertEquals(3, p.items.size)
        val first = p.items[0]
        assertEquals(com.hdlee73.everylaw.core.model.ItemPath(paragraph = 1), first.path)
        assertEquals(2, first.children.size)
        assertEquals(com.hdlee73.everylaw.core.model.ItemPath(1, "2", "나"), first.children[1].children[1].path)
        assertTrue(p.items[1].text.contains("이어지는 설명"))
    }

    @Test fun lawBodyDoesNotRepeatNumbers() {
        val xml = """<법령><기본정보><법령명_한글>테스트법</법령명_한글></기본정보><조문>
            <조문단위><조문번호>9</조문번호><조문여부>조문</조문여부><조문제목>정의</조문제목><조문내용>제9조(정의)</조문내용>
            <항><항번호>⑮</항번호><항내용>⑮ 이 법에서 다음과 같다.</항내용>
              <호><호번호>1.</호번호><호내용>1. 상장법인</호내용></호></항></조문단위></조문></법령>"""
        val a = DrfParsers.parseLawBody(xml).articles.single()
        assertEquals(listOf("제9조(정의)", "⑮ 이 법에서 다음과 같다.", "1. 상장법인"), a.content.lines())
        assertEquals(1, a.parsed.items.size)
        assertEquals("⑮ 이 법에서 다음과 같다.\n  1. 상장법인", a.body)
    }
}

class ItemQueryTest {
    private fun ok(s: String) = (LawQueryParser.parse(s) as LawQueryParser.Result.Ok).query

    @Test fun parsesParagraphSubparagraphAndItem() {
        val q = ok("자본시장법 제86조 제2항 제3호 가목")
        assertEquals("자본시장과 금융투자업에 관한 법률", q.lawName)
        assertEquals(ArticleNo("86"), q.article)
        assertEquals(com.hdlee73.everylaw.core.model.ItemPath(2, "3", "가"), q.path)
        assertEquals("제86조 제2항 제3호 가목", q.target)
    }

    @Test fun compactAndBranchForms() {
        val q = ok("은행법 34조의2 1항 2호")
        assertEquals("은행법", q.lawName)
        assertEquals(ArticleNo("34", 2), q.article)
        assertEquals(com.hdlee73.everylaw.core.model.ItemPath(1, "2", null), q.path)
        assertTrue(ok("자본시장법 제11조").path.isEmpty)
    }
}

class AnchorTest {
    private val no = ArticleNo("420")
    @Test fun anchorsToCitedItem() {
        val a = DelegationTreeBuilder.anchorOf("법 제420조제1항제5호에 따라", DelegationTreeBuilder.LAW_PREFIX, no)
        assertEquals(com.hdlee73.everylaw.core.model.ItemPath(1, "5", null), a)
        assertEquals(com.hdlee73.everylaw.core.model.ItemPath(), DelegationTreeBuilder.anchorOf("법 제420조에 따라", DelegationTreeBuilder.LAW_PREFIX, no))
        assertEquals(null, DelegationTreeBuilder.anchorOf("법 제4200조제1항", DelegationTreeBuilder.LAW_PREFIX, no))
    }
}

class AttachmentTest {
    private fun art(c: String) = com.hdlee73.everylaw.core.model.Article(ArticleNo("1"), "", c)
    private fun node(id: String, anchor: com.hdlee73.everylaw.core.model.ItemPath?) =
        com.hdlee73.everylaw.core.model.LawNode(id, LawLevel.DECREE, "령", art("x"), anchor = anchor)

    @Test fun nodesLandUnderTheirItemOrNearestParent() {
        val a = art("제1조(정의) ① 가나다\n1. 하나\n2. 둘\n② 라마바")
        val map = com.hdlee73.everylaw.core.domain.ItemAttachment.attach(
            a,
            listOf(
                node("a", com.hdlee73.everylaw.core.model.ItemPath(1, "2", null)),
                node("b", com.hdlee73.everylaw.core.model.ItemPath(1, "9", null)),
                node("c", com.hdlee73.everylaw.core.model.ItemPath(7, null, null)),
                node("d", null),
            ),
        )
        assertEquals(listOf("a"), map[com.hdlee73.everylaw.core.model.ItemPath(1, "2", null)]!!.map { it.id })
        assertEquals(listOf("b"), map[com.hdlee73.everylaw.core.model.ItemPath(1, null, null)]!!.map { it.id })
        assertEquals(listOf("c", "d"), map[com.hdlee73.everylaw.core.model.ItemPath()]!!.map { it.id })
    }
}
