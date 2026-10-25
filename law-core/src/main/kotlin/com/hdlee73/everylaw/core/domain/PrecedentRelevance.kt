package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.PrecedentDetail

/**
 * 판례가 특정 법령의 특정 조문을 실제로 인용했는지 가려낸다.
 * 법령명 바로 뒤에 이어지는 조 번호만 인정하므로, 다른 법령의 같은 조 번호("증권거래법 제55조")는 걸러진다.
 */
object PrecedentRelevance {

    enum class Where(val rank: Int, val label: String) {
        REFERENCE(3, "참조조문에 인용"),
        GIST(2, "판결요지에 인용"),
        BODY(1, "본문에 인용"),
    }

    private val PAREN = Regex("""\([^)]*\)""")
    /** 다른 법령·하위 법규의 이름처럼 보이는 낱말: 인용 구간의 끝을 알린다 */
    private val OTHER_LAW = Regex("""(?<![가-힣])[가-힣]+(?:법률|법|령|규칙)(?![가-힣])""")
    private const val WINDOW = 160

    fun where(d: PrecedentDetail, lawNames: List<String>, article: ArticleNo): Where? = when {
        cites(d.referencedArticles, lawNames, article) -> Where.REFERENCE
        cites(d.gist + "\n" + d.holdings, lawNames, article) -> Where.GIST
        cites(d.fullText, lawNames, article) -> Where.BODY
        else -> null
    }

    fun cites(text: String, lawNames: List<String>, article: ArticleNo): Boolean {
        if (text.isBlank()) return false
        val clean = text.replace(PAREN, " ")
        val articleRegex = articleRegex(article)
        for (name in lawNames.filter { it.isNotBlank() }.distinct()) {
            val nameRegex = Regex(name.filter { !it.isWhitespace() }.map { Regex.escape(it.toString()) }.joinToString("""\s*"""))
            for (m in nameRegex.findAll(clean)) {
                val rest = clean.substring(m.range.last + 1).take(WINDOW)
                val cut = OTHER_LAW.find(rest)?.range?.first ?: rest.length
                if (articleRegex.containsMatchIn(rest.substring(0, cut))) return true
            }
        }
        return false
    }

    private fun articleRegex(a: ArticleNo): Regex {
        val main = Regex.escape(a.main)
        return if (a.branch == null) Regex("""제\s*$main\s*조(?!\s*의\s*\d)""")
        else Regex("""제\s*$main\s*조\s*의\s*${a.branch}(?!\d)""")
    }
}
