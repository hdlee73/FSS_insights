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
        decreeNodes.forEach { d -> d.anchor = lawNo?.let { anchorOf(d.article.content, LAW_PREFIX, it) } ?: ItemPath() }

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

        /** [text]가 "[prefix] 제N조(의M)" 를 참조하는지. 제12조가 제120조·제12조의2와 섞이지 않게 경계를 본다. */
        fun refersTo(text: String, prefix: String, no: ArticleNo): Boolean {
            val main = Regex.escape(no.main)
            val branch = no.branch?.let { """\s*의\s*$it""" } ?: """(?!\s*의\s*\d)"""
            val pattern = """$prefix\s*제\s*$main\s*조$branch(?![\d-])"""
            return Regex(pattern).containsMatchIn(text)
        }
    }
}
