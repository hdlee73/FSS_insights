package io.github.hdlee73.financenewsradar.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LawCitationTest {
    @Test fun parsesArticleAndParagraph() {
        assertEquals(ArticleRef("자본시장법", 9, 0, 5), LawCitation.parse("자본시장법 제9조 제5항"))
    }

    @Test fun parsesCompactInputWithoutJe() {
        assertEquals(ArticleRef("자본시장법", 9, 0, 5), LawCitation.parse("자본시장법 9조5항"))
        assertEquals(ArticleRef("자본시장법", 9, 0, 5), LawCitation.parse("자본시장법제9조제5항"))
    }

    @Test fun parsesBranchItemAndSubItem() {
        assertEquals(
            ArticleRef("금소법", 17, 2, 3, 1, 0, "가"),
            LawCitation.parse("금소법 제17조의2 제3항 제1호 가목")
        )
        assertEquals(ArticleRef("은행법", 2, 0, null, 1, 2), LawCitation.parse("은행법 제2조 제1호의2"))
    }

    @Test fun stripsBracketsAndTrailingPunctuation() {
        assertEquals(ArticleRef("전자금융거래법", 2), LawCitation.parse("「전자금융거래법」 제2조"))
        assertEquals(ArticleRef("전자금융거래법", 2), LawCitation.parse("전자금융거래법 제2조."))
    }

    @Test fun parsesCircledParagraphNumber() {
        assertEquals(ArticleRef("자본시장법", 9, 0, 5), LawCitation.parse("자본시장법 제9조 ⑤"))
    }

    @Test fun rejectsInputWithoutLawNameOrArticle() {
        assertNull(LawCitation.parse(""))
        assertNull(LawCitation.parse("제9조"))
        assertNull(LawCitation.parse("자본시장법"))
        assertNull(LawCitation.parse("자본시장법 제5항"))
    }

    @Test fun rejectsSubItemWithoutItem() {
        assertNull(LawCitation.parse("자본시장법 제9조 가목"))
    }

    @Test fun buildsJoCode() {
        assertEquals("000300", ArticleRef("x", 3).joCode)
        assertEquals("001002", ArticleRef("x", 10, 2).joCode)
        assertEquals("035000", ArticleRef("x", 350).joCode)
    }

    @Test fun formatsArticlePartWithoutSpaces() {
        assertEquals("제9조제5항", LawCitation.articlePart(ArticleRef("x", 9, 0, 5)))
        assertEquals("제17조의2제3항제1호가목", LawCitation.articlePart(ArticleRef("x", 17, 2, 3, 1, 0, "가")))
        assertEquals("제2조제1호의2", LawCitation.articlePart(ArticleRef("x", 2, 0, null, 1, 2)))
    }

    @Test fun resolvesAbbreviations() {
        assertEquals("금융소비자 보호에 관한 법률", LawCitation.resolveLawName("금소법"))
        assertEquals("자본시장과 금융투자업에 관한 법률", LawCitation.resolveLawName("자본 시장법"))
        assertEquals("민법", LawCitation.resolveLawName("민법"))
    }

    @Test fun formatsCitationForReports() {
        val ref = LawCitation.parse("자본시장법 제9조 제5항")!!
        assertEquals("「자본시장과 금융투자업에 관한 법률」 제9조제5항", LawCitation.format(ref))
        assertEquals("「민법」 제750조", LawCitation.format(ArticleRef("민법", 750)))
    }

    @Test fun citationCarriesEffectiveDate() {
        val ref = ArticleRef("은행법", 2)
        assertEquals(
            "「은행법」 제2조 (2025. 3. 1. 시행)",
            LawCitation.formatWithEffectiveDate(ref, LocalDate.of(2025, 3, 1))
        )
    }
}
