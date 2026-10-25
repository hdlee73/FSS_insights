package com.hdlee73.everylaw.core

import com.hdlee73.everylaw.core.domain.CompareTable
import com.hdlee73.everylaw.core.domain.DelegationTreeBuilder
import com.hdlee73.everylaw.core.domain.ItemAttachment
import com.hdlee73.everylaw.core.domain.selectForPath
import com.hdlee73.everylaw.core.domain.stableKey
import com.hdlee73.everylaw.core.domain.withParagraphPieces
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDelegationTest {
    private val law = Article(
        ArticleNo.of("85"), "불건전 영업행위의 금지",
        "제85조(불건전 영업행위의 금지) 집합투자업자는 다음 각 호의 행위를 하여서는 아니 된다. 다만, 대통령령으로 정하는 경우에는 이를 할 수 있다.\n" +
            "1. 가격에 영향을 미치는 매매\n2. 자기 또는 대통령령으로 정하는 관계인수인이 인수한 증권을 매수하는 행위",
    )
    private val decree = Article(
        ArticleNo.of("87"), "불건전 영업행위의 금지",
        "제87조(불건전 영업행위의 금지) ① 법 제85조 각 호 외의 부분 단서에서 \"대통령령으로 정하는 경우\"란 다음 각 호의 경우를 말한다.\n" +
            "1. 첫째 경우\n② 법 제85조제2호에서 \"대통령령으로 정하는 관계인수인\"이란 계열회사를 말한다.",
    )
    private val rule = Article(ArticleNo.of("7"), "세부", "제7조(세부) 영 제87조제2항에 따른 관계인수인의 확인 방법은 다음과 같다.")

    private fun tree() = DelegationTreeBuilder().build(
        DelegationTreeBuilder.Input("자본시장법", law, "자본시장법 시행령", listOf(decree), "자본시장법 시행규칙", listOf(rule), emptyList(), emptyList()),
    )

    @Test fun leadCitationIsAnchoredToLead() {
        assertEquals(ItemPath.LEAD, DelegationTreeBuilder.anchorOf("법 제85조 각 호 외의 부분 단서에서", DelegationTreeBuilder.LAW_PREFIX, ArticleNo.of("85")))
        assertEquals(ItemPath(), DelegationTreeBuilder.anchorOf("법 제85조에 따라", DelegationTreeBuilder.LAW_PREFIX, ArticleNo.of("85")))
    }

    @Test fun decreeIsSplitPerParagraphAndAttachedInPlace() {
        val split = tree().withParagraphPieces()
        assertEquals(listOf("제1항", "제2항"), split.children.map { it.part })
        assertEquals(listOf(ItemPath.LEAD, ItemPath(subparagraph = "2")), split.children.map { it.anchor })
        // 시행규칙은 영 제87조제2항을 인용했으니 제2항 조각 밑
        assertTrue(split.children[0].children.isEmpty())
        assertEquals(1, split.children[1].children.size)
        val attached = ItemAttachment.attach(split.article, split.children)
        assertEquals(setOf(ItemPath.LEAD, ItemPath(subparagraph = "2")), attached.keys)
        assertNotEquals(split.children[0].stableKey, split.children[1].stableKey)
    }

    @Test fun itemQueryFindsDecreeParagraphAfterSplit() {
        val (kept, note) = selectForPath(tree().withParagraphPieces().children, ItemPath(subparagraph = "2"))
        assertEquals(listOf("제2항"), kept.map { it.part })
        assertEquals(null, note)
    }

    @Test fun itemQueryWithoutDirectCitationFallsBackToWholeArticle() {
        val (kept, note) = selectForPath(tree().withParagraphPieces().children, ItemPath(subparagraph = "1"))
        assertEquals(2, kept.size)
        assertTrue(note != null)
    }

    @Test fun singleLocationIsNotSplit() {
        val d = decree.copy(content = "제87조(x) ① 법 제85조제2호에 따라 가.\n② 법 제85조제2호에 따라 나.")
        val t = DelegationTreeBuilder().build(
            DelegationTreeBuilder.Input("자본시장법", law, "령", listOf(d), null, emptyList(), emptyList(), emptyList()),
        )
        assertEquals(1, t.withParagraphPieces().children.size)
    }

    @Test fun compareRowsAlignLawPartsWithDelegations() {
        val rows = CompareTable.build(tree(), setOf(LawLevel.DECREE, LawLevel.RULE))
        assertEquals(2, rows.size)
        assertTrue(rows[0].lawText.startsWith("제85조(불건전 영업행위의 금지)"))
        assertTrue(rows[0].lawText.contains("다만"))
        assertEquals(listOf(LawLevel.DECREE), rows[0].cells.keys.toList())
        assertTrue(rows[1].lawText.contains("1. 가격에"))
        assertTrue(rows[1].lawText.contains("2. 자기"))
        assertEquals(setOf(LawLevel.DECREE, LawLevel.RULE), rows[1].cells.keys)
    }

    @Test fun onlyChosenLevelsBecomeColumnsAndEmptyRowsMerge() {
        // 시행령은 고르지 않고 시행규칙만: 제1항(시행령) 줄은 비므로 법률 글이 시행규칙이 있는 줄에 합쳐진다
        val rows = CompareTable.build(tree(), setOf(LawLevel.RULE))
        assertEquals(1, rows.size)
        assertEquals(listOf(LawLevel.RULE), rows[0].cells.keys.toList())
        assertTrue(rows[0].lawText.contains("다만") && rows[0].lawText.contains("2. 자기"))
        assertEquals(listOf(LawLevel.LAW, LawLevel.RULE), CompareTable.columns(setOf(LawLevel.RULE)))
    }
}
