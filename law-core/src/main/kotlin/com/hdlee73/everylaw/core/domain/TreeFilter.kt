package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawNode

/**
 * 검색 결과 트리 안에서의 걸러내기·숨기기.
 *
 * 노드 id("sup-0-2")는 조회 순서에 따라 달라질 수 있어 저장용으로 쓰지 않고,
 * 단계+법령명+조문번호로 만든 [stableKey]를 쓴다.
 */
val LawNode.stableKey: String
    get() = "${level.name}|$sourceName|${article.number?.display ?: article.title}" + part?.let { "#$it" }.orEmpty()

/** 이 트리에서 [hidden] 키에 해당하는 노드(와 그 아래 전체)를 뺀 사본. 루트는 숨기지 않는다. */
fun LawNode.withoutHidden(hidden: Set<String>): LawNode =
    if (hidden.isEmpty()) this
    else copy(children = children.filter { it.stableKey !in hidden }.map { it.withoutHidden(hidden) })

/** [hidden] 중 이 트리에 실제로 있는 노드 수(숨김 배너의 개수). */
fun LawNode.hiddenCount(hidden: Set<String>): Int =
    if (hidden.isEmpty()) 0
    else children.sumOf { if (it.stableKey in hidden) 1 else it.hiddenCount(hidden) }

object TreeFilter {
    private val ARTICLE_QUERY = Regex("""^제?\s*(\d+(?:-\d+)*)\s*조(?:\s*의\s*(\d+))?$""")

    /** 입력이 "제12조", "12조", "제12조의2" 형태면 그 조문번호, 아니면 null */
    fun articleNo(query: String): ArticleNo? {
        val m = ARTICLE_QUERY.find(query.trim()) ?: return null
        return ArticleNo.of(m.groupValues[1], m.groupValues[2].toIntOrNull())
    }

    /** 노드 하나가 검색어와 직접 맞는지(제목·본문·법령명, 또는 조문번호) */
    fun matches(node: LawNode, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val no = articleNo(q)
        if (no != null) return node.article.number == no
        return node.article.heading.contains(q, ignoreCase = true) ||
            node.article.content.contains(q, ignoreCase = true) ||
            node.sourceName.contains(q, ignoreCase = true)
    }

    /**
     * 검색어에 맞는 노드와 그 상위 경로만 남긴 사본. 루트는 항상 남기고, 맞는 노드의 하위는 그대로 둔다.
     * 맞는 것이 없으면 자식이 빈 루트가 된다.
     */
    fun filter(root: LawNode, query: String): LawNode {
        if (query.isBlank()) return root
        return root.copy(children = root.children.mapNotNull { keep(it, query) })
    }

    private fun keep(node: LawNode, query: String): LawNode? {
        if (matches(node, query)) return node
        val kept = node.children.mapNotNull { keep(it, query) }
        return if (kept.isEmpty()) null else node.copy(children = kept)
    }

    /** 걸러진 결과에서 검색어에 직접 맞는 노드 수(루트 제외) */
    fun matchCount(root: LawNode, query: String): Int =
        if (query.isBlank()) 0 else root.flatten().drop(1).count { matches(it, query) }
}
