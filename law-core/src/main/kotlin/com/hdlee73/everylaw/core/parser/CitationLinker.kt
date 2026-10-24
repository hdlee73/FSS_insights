package com.hdlee73.everylaw.core.parser

import com.hdlee73.everylaw.core.model.ArticleNo

/**
 * 조문 본문 속 인용("제12조", "법 제12조", "영 제16조", 「은행법」 제34조)을 찾아
 * 눌렀을 때 검색할 질의("자본시장법 제12조")로 바꾼다.
 */
object CitationLinker {
    /** 본문 인용이 어느 법령 기준인지. [ownName]이 null이면 "제N조"만 있는 인용은 연결하지 않는다. */
    data class Context(
        val lawName: String,
        val decreeName: String? = null,
        val ruleName: String? = null,
        val ownName: String? = lawName,
    )

    data class Citation(val start: Int, val end: Int, val query: String)

    private const val NO = """제\s*(\d+)\s*조(?:\s*의\s*(\d+))?"""
    private val BRACKETED = Regex("""「([^」]+)」\s*$NO""")
    private val PREFIXED = Regex("""(같은\s*)?([가-힣]*(?:법률|법|시행령|령|시행규칙|규칙)|영)\s*$NO""")
    private val BARE = Regex(NO)

    /** 법령 이름이 아닌데 "령"·"법"으로 끝나는 말 */
    private val NOT_A_LAW = setOf("대통령령", "총리령", "부령", "법령", "방법", "민법상", "이법")

    fun find(text: String, ctx: Context): List<Citation> {
        val out = mutableListOf<Citation>()
        fun overlaps(s: Int, e: Int) = out.any { s < it.end && e > it.start }
        fun add(m: MatchResult, name: String?, noGroup: Int) {
            if (name.isNullOrBlank() || overlaps(m.range.first, m.range.last + 1)) return
            val no = ArticleNo.of(m.groupValues[noGroup], m.groupValues[noGroup + 1].toIntOrNull())
            out += Citation(m.range.first, m.range.last + 1, "$name ${no.display}")
        }

        BRACKETED.findAll(text).forEach { add(it, it.groupValues[1].trim(), 2) }
        PREFIXED.findAll(text).forEach { m ->
            val word = m.groupValues[2]
            if (word in NOT_A_LAW) return@forEach
            val name = when (word) {
                "법", "법률" -> ctx.lawName
                "영", "시행령" -> ctx.decreeName ?: "${ctx.lawName} 시행령"
                "규칙", "시행규칙" -> ctx.ruleName ?: "${ctx.lawName} 시행규칙"
                else -> if (word.length >= 3 && m.groupValues[1].isBlank()) word else null
            }
            add(m, name, 3)
        }
        BARE.findAll(text).forEach { m -> add(m, ctx.ownName, 1) }
        return out.sortedBy { it.start }
    }
}
