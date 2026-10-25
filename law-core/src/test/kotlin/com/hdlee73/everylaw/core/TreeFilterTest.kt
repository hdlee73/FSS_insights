package com.hdlee73.everylaw.core

import com.hdlee73.everylaw.core.domain.TreeFilter
import com.hdlee73.everylaw.core.domain.hiddenCount
import com.hdlee73.everylaw.core.domain.stableKey
import com.hdlee73.everylaw.core.domain.withoutHidden
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFilterTest {
    private fun node(id: String, level: LawLevel, no: Int, title: String, content: String = "", vararg kids: LawNode) =
        LawNode(id, level, "테스트법 ${level.label}", Article(ArticleNo.of(no.toString()), title, content), children = kids.toList())

    private val tree = node(
        "law", LawLevel.LAW, 55, "손실보전 등의 금지", "손실을 보전하여서는 아니 된다",
        node(
            "d0", LawLevel.DECREE, 60, "손실보전 예외", "투자일임",
            node("s0", LawLevel.SUPERVISORY_REGULATION, 4, "세부기준", "금융투자업자는 ..."),
        ),
        node("d1", LawLevel.DECREE, 61, "신탁업", "신탁 관련"),
    )

    @Test fun articleNumberQueryMatchesOnlyThatArticle() {
        assertEquals(ArticleNo.of("60"), TreeFilter.articleNo("제60조"))
        assertEquals(ArticleNo.of("60"), TreeFilter.articleNo("60조"))
        assertNull(TreeFilter.articleNo("손실"))
        val f = TreeFilter.filter(tree, "제61조")
        assertEquals(listOf("d1"), f.children.map { it.id })
    }

    @Test fun keywordKeepsAncestorsOfDeepMatch() {
        val f = TreeFilter.filter(tree, "금융투자업자")
        assertEquals(listOf("d0"), f.children.map { it.id })
        assertEquals(listOf("s0"), f.children[0].children.map { it.id })
        assertEquals(1, TreeFilter.matchCount(f, "금융투자업자"))
    }

    @Test fun blankQueryReturnsSame() {
        assertTrue(TreeFilter.filter(tree, "  ") === tree)
    }

    @Test fun noMatchLeavesEmptyRoot() {
        assertTrue(TreeFilter.filter(tree, "존재하지않음").children.isEmpty())
    }

    @Test fun hidingRemovesSubtreeAndCounts() {
        val key = tree.children[0].stableKey
        val pruned = tree.withoutHidden(setOf(key))
        assertEquals(listOf("d1"), pruned.children.map { it.id })
        assertEquals(1, tree.hiddenCount(setOf(key)))
        assertEquals(0, tree.hiddenCount(emptySet()))
    }

    @Test fun stableKeyIgnoresNodeId() {
        val a = node("x-1", LawLevel.DECREE, 60, "t")
        val b = node("y-9", LawLevel.DECREE, 60, "t")
        assertEquals(a.stableKey, b.stableKey)
    }
}
