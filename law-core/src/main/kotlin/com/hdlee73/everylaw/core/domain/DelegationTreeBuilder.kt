package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.AdminRuleDocument
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode

/**
 * 조문들의 "위임 문구"를 근거로 상하위 트리를 조립한다. 네트워크와 무관한 순수 로직이라 단위 테스트한다.
 *
 * 연결 규칙
 * - 시행령: 3단비교가 법률 조문과 같은 줄로 묶어 준 조문
 * - 시행규칙: 본문에 "영 제N조"/"시행령 제N조"가 있으면 그 시행령 밑, 없으면 첫 시행령(없으면 법률) 밑
 * - 감독규정: 본문이 참조하는 가장 하위 조문(시행규칙 > 시행령 > 법률) 밑
 * - 시행세칙: 본문이 참조하는 감독규정 조문("규정 제2-3조") 밑
 */
class DelegationTreeBuilder(
    private val maxPerParent: Int = 30,
) {
    data class Input(
        val lawName: String,
        val lawArticle: Article,
        val decreeName: String?,
        val decrees: List<Article>,
        val ruleName: String?,
        val rules: List<Article>,
        val supervisory: List<AdminRuleDocument>,
        val details: List<AdminRuleDocument>,
    )

    fun build(input: Input): LawNode {
        val lawNo = input.lawArticle.number
        val decreeNodes = input.decrees.mapIndexed { i, a ->
            MutableNode("decree-$i", LawLevel.DECREE, input.decreeName ?: "${input.lawName} 시행령", a, null)
        }
        val ruleNodes = input.rules.mapIndexed { i, a ->
            MutableNode("rule-$i", LawLevel.RULE, input.ruleName ?: "${input.lawName} 시행규칙", a, null)
        }
        val root = MutableNode("law", LawLevel.LAW, input.lawName, input.lawArticle, null)
        root.children += decreeNodes
        decreeNodes.forEach { d -> d.anchor = lawNo?.let { delegationAnchor(d.article.content, it) } ?: ItemPath() }

        ruleNodes.forEach { rule ->
            val parent = decreeNodes.firstOrNull { d -> d.article.number?.let { refersTo(rule.article.content, DECREE_PREFIX, it) } == true }
                ?: decreeNodes.firstOrNull()
                ?: root
            parent.children += rule
            if (parent === root) rule.anchor = lawNo?.let { anchorOf(rule.article.content, LAW_PREFIX, it) } ?: ItemPath()
        }

        val supNodes = mutableListOf<MutableNode>()
        input.supervisory.forEachIndexed { di, doc ->
            doc.articles.forEachIndexed { ai, art ->
                val parent = ruleNodes.firstOrNull { r -> r.article.number?.let { refersTo(art.content, RULE_PREFIX, it) } == true }
                    ?: decreeNodes.firstOrNull { d -> d.article.number?.let { refersTo(art.content, DECREE_PREFIX, it) } == true }
                    ?: root.takeIf { lawNo != null && refersTo(art.content, LAW_PREFIX, lawNo) }
                    ?: return@forEachIndexed
                if (parent.children.size >= maxPerParent) return@forEachIndexed
                val node = MutableNode("sup-$di-$ai", LawLevel.SUPERVISORY_REGULATION, doc.name, art, doc.agency)
                parent.children += node
                if (parent === root) node.anchor = lawNo?.let { anchorOf(art.content, LAW_PREFIX, it) } ?: ItemPath()
                supNodes += node
            }
        }

        input.details.forEachIndexed { di, doc ->
            doc.articles.forEachIndexed { ai, art ->
                val parent = supNodes.firstOrNull { s ->
                    s.article.number?.let { no -> refersTo(art.content, SUPERVISORY_PREFIX, no) || (no.main.contains('-') && refersTo(art.content, "", no)) } == true
                } ?: return@forEachIndexed
                if (parent.children.size >= maxPerParent) return@forEachIndexed
                parent.children += MutableNode("det-$di-$ai", LawLevel.ENFORCEMENT_DETAIL, doc.name, art, doc.agency)
            }
        }
        return root.freeze()
    }

    /** [build]의 결과를 위임 트리와 "인용된 법규"로 나눈 것. */
    data class Split(val root: LawNode, val cited: List<LawNode>)

    /**
     * 법률 조문 바로 아래에 붙은 규정 중 법 조문을 인용만 하고 그 위치를 위임받지는 않은 것을 따로 뺀다.
     * 인용한 위치(또는 그 아래)에 위임 문구가 없으면 인용된 법규다. 본문에 법 조문 인용이 없는데 3단비교가 연결해 준 것은 위임으로 믿는다.
     * 빠진 규정에 딸린 하위 규정은 그대로 함께 간다.
     */
    fun buildSplit(input: Input): Split {
        val tree = build(input)
        val lawNo = input.lawArticle.number ?: return Split(tree, emptyList())
        val infos = tree.children.map { c -> c to citeInfo(c.article, lawNo) }
        // 다른 규정이 위임을 되풀이해 정의한 위치(직접 위임)
        val strongAnchors = infos.map { (c, i) -> c to i.strong.filter { DelegationPhrase.delegatesAt(input.lawArticle, it) } }
        val kept = mutableListOf<LawNode>()
        val cited = mutableListOf<LawNode>()
        for ((c, info) in infos) {
            val ownStrong = strongAnchors.first { it.first === c }.second
            val direct = when {
                ownStrong.isNotEmpty() -> true
                info.weak.isEmpty() -> true // 법 조문 인용이 없는데 3단비교가 연결해 준 것은 위임으로 믿는다
                info.weak.none { DelegationPhrase.delegatesAt(input.lawArticle, it) } -> false
                // 위임 문구가 있는 위치를 "법 제N조제K호에 따른 …"처럼 인용만 했고, 같은 위치의 위임을 다른 규정이 직접 정의했으면 단순 인용
                else -> strongAnchors.none { (o, anchors) -> o !== c && anchors.any { sa -> info.weak.any { w -> sa.startsWith(w) || w.startsWith(sa) } } }
            }
            (if (direct) kept else cited) += c
        }
        return Split(tree.copy(children = sortByAnchor(kept)), cited)
    }

    private class CiteInfo(val weak: List<ItemPath>, val strong: List<ItemPath>)

    /**
     * 하위 규정이 법 조문을 인용한 위치들. [CiteInfo.strong]은 `법 제71조제3호에서 "대통령령으로 정하는 …"란`처럼 위임을 되풀이해 정의한 꼴,
     * [CiteInfo.weak]는 그 밖의 모든 인용(`법 제71조제3호에 따른 기업금융업무`처럼 단순 인용 포함).
     */
    private fun citeInfo(sub: Article, lawNo: ArticleNo): CiteInfo {
        val parsed = sub.parsed
        val texts = listOf(parsed.lead) + parsed.items.map { p -> p.flatten().joinToString("\n") { it.text } }
        val weak = texts.mapNotNull { anchorOf(it, LAW_PREFIX, lawNo) }.ifEmpty { listOfNotNull(anchorOf(sub.content, LAW_PREFIX, lawNo)) }
        val strong = texts.flatMap { restatedAnchors(it, lawNo) }.ifEmpty { restatedAnchors(sub.content, lawNo) }
        return CiteInfo(weak, strong)
    }

    private class MutableNode(
        val id: String,
        val level: LawLevel,
        val source: String,
        val article: Article,
        val issuer: String?,
        val children: MutableList<MutableNode> = mutableListOf(),
    ) {
        var anchor: ItemPath? = null
        fun freeze(): LawNode = LawNode(id, level, source, article, issuer, children.map { it.freeze() }, anchor)
    }

    companion object {
        // 앞에 한글이 붙지 않은 "법"/"영"/"규칙"/"규정" 이어야 다른 법(예: 은행법)과 혼동하지 않는다.
        const val LAW_PREFIX = """(?<![가-힣])법"""
        const val DECREE_PREFIX = """(?:(?<![가-힣])영|시행령)"""
        const val RULE_PREFIX = """(?:(?<![가-힣])규칙|시행규칙)"""
        private val LEAD_FOLLOWS = Regex("""^\s*(?:각\s*호\s*외의\s*부분|본문|단서)""")
        const val SUPERVISORY_PREFIX = """(?:(?<![가-힣])규정|감독규정)"""

        /**
         * [text]가 "[prefix] 제N조(의M)" 뒤에 "제K항 제J호 X목"을 붙여 인용했다면 그 위치.
         * 조만 인용했으면 빈 위치, 인용이 없으면 null. 여러 번 인용했으면 처음 것.
         */
        fun anchorOf(text: String, prefix: String, no: ArticleNo): ItemPath? {
            val main = Regex.escape(no.main)
            val branch = no.branch?.let { """\s*의\s*$it""" } ?: """(?!\s*의\s*\d)"""
            val pattern = Regex(
                """$prefix\s*제\s*$main\s*조$branch(?![\d-])""" +
                    """(?:\s*제\s*(\d+)\s*항)?(?:\s*제\s*(\d+(?:\s*의\s*\d+)?)\s*호)?(?:\s*([가-힣])\s*목)?"""
            )
            val m = pattern.find(text) ?: return null
            val path = ItemPath(
                paragraph = m.groupValues[1].toIntOrNull(),
                subparagraph = m.groupValues[2].takeIf { it.isNotEmpty() }?.replace(Regex("""\s+"""), ""),
                item = m.groupValues[3].takeIf { it.isNotEmpty() },
            )
            // "법 제85조 각 호 외의 부분 단서" 처럼 호 앞 본문·단서를 가리키면 조 전체가 아니라 그 부분
            if (path.isEmpty && LEAD_FOLLOWS.containsMatchIn(text.substring(m.range.last + 1).take(20))) return ItemPath.LEAD
            return path
        }

        /**
         * 위임을 되풀이해 정의하는 꼴의 인용("법 제N조제K항제J호에서 "…"", "…에서 위임한", "…에서 대통령령으로")의 위치.
         * 그런 꼴이 없으면 null. 같은 꼴이 여러 번이면 처음 것.
         */
        fun restatedAnchor(text: String, no: ArticleNo): ItemPath? = restatedAnchors(text, no).firstOrNull()

        fun restatedAnchors(text: String, no: ArticleNo): List<ItemPath> {
            val main = Regex.escape(no.main)
            val branch = no.branch?.let { """\s*의\s*$it""" } ?: """(?!\s*의\s*\d)"""
            val pattern = Regex(
                """$LAW_PREFIX\s*제\s*$main\s*조$branch(?![\d-])""" +
                    """(?:\s*제\s*(\d+)\s*항)?(?:\s*제\s*(\d+(?:\s*의\s*\d+)?)\s*호)?(?:\s*([가-힣])\s*목)?""" +
                    """([^\n"“”]{0,25}?)에서\s*(?:["“][^"”\n]{1,80}["”]|위임|(?:대통령령|총리령|[가-힣]{1,12}부령)으로)"""
            )
            return pattern.findAll(text).map { m ->
                val path = ItemPath(
                    paragraph = m.groupValues[1].toIntOrNull(),
                    subparagraph = m.groupValues[2].takeIf { it.isNotEmpty() }?.replace(Regex("""\s+"""), ""),
                    item = m.groupValues[3].takeIf { it.isNotEmpty() },
                )
                if (path.isEmpty && LEAD_FOLLOWS.containsMatchIn(m.groupValues[4])) ItemPath.LEAD else path
            }.toList()
        }

        /** 위임을 되풀이해 정의한 인용이 있으면 그 위치, 없으면 처음 인용한 위치. */
        fun delegationAnchor(text: String, no: ArticleNo): ItemPath? = restatedAnchor(text, no) ?: anchorOf(text, LAW_PREFIX, no)

        /** 위치의 법 조문 속 순서: 조 전체 < 각 호 외의 부분 < 항 < 호 < 목. */
        private val PATH_ORDER: Comparator<ItemPath> = Comparator { a, b ->
            fun nums(s: String?) = s?.split("의")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            fun cmpList(x: List<Int>, y: List<Int>): Int {
                for (i in 0 until maxOf(x.size, y.size)) {
                    val c = (x.getOrNull(i) ?: -1).compareTo(y.getOrNull(i) ?: -1)
                    if (c != 0) return c
                }
                return 0
            }
            // 항 없이 호만 있는 위치(항이 없는 조)는 각 호 외의 부분(0) 뒤, 조 전체(빈 위치)는 맨 앞
            fun pk(x: ItemPath) = x.paragraph ?: if (x.isEmpty) -1 else 1
            val p = pk(a).compareTo(pk(b))
            if (p != 0) return@Comparator p
            val s = cmpList(nums(a.subparagraph), nums(b.subparagraph))
            if (s != 0) return@Comparator s
            (a.item ?: "").compareTo(b.item ?: "")
        }

        /** 위임한 법 조문의 항·호·목 순서로 정렬한다. 같은 위치면 원래 순서(조 번호순)를 지킨다. */
        fun sortByAnchor(nodes: List<LawNode>): List<LawNode> =
            nodes.sortedWith { a, b -> PATH_ORDER.compare(a.anchor ?: ItemPath(), b.anchor ?: ItemPath()) }

        /** [text]가 "[prefix] 제N조(의M)" 를 참조하는지. 제12조가 제120조·제12조의2와 섞이지 않게 경계를 본다. */
        fun refersTo(text: String, prefix: String, no: ArticleNo): Boolean {
            val main = Regex.escape(no.main)
            val branch = no.branch?.let { """\s*의\s*$it""" } ?: """(?!\s*의\s*\d)"""
            val pattern = """$prefix\s*제\s*$main\s*조$branch(?![\d-])"""
            return Regex(pattern).containsMatchIn(text)
        }
    }
}
