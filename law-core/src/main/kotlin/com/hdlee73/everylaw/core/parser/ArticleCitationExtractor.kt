package com.hdlee73.everylaw.core.parser

/** 임의의 글(금감원 공개정보 본문 등)에서 찾은 "법령명 제N조" 인용 한 건. [query]는 앱 검색창에 그대로 넣을 수 있다. */
data class ExtractedCitation(
    val lawName: String,
    val article: String,
    val query: String,
    /** 글에서 실제로 쓰인 표기 (예: "자본시장법 시행령 제55조제2항") */
    val text: String,
    val count: Int,
)

/**
 * 글 속의 "자본시장법 제55조", "「금융소비자 보호에 관한 법률」 제19조제2항", "자본시장법 시행령 제68조" 같은 인용을 찾는다.
 * 조 번호 바로 앞에 알려진 법령명(약칭 사전) 또는 「 」로 묶인 이름이 있을 때만 인정하므로,
 * 법령명을 알 수 없는 "제5조"는 건너뛴다(엉뚱한 법령으로 연결하지 않기 위해).
 */
object ArticleCitationExtractor {

    private val ARTICLE = Regex("""제\s*(\d+)\s*조(?:\s*의\s*(\d+))?(?:\s*제\s*(\d+)\s*항)?""")
    private val SUFFIX = Regex("""\s*(시행령|시행규칙)\s*$""")
    private val QUOTED = Regex("""「([^」]{2,60})」\s*(시행령|시행규칙)?\s*$""")

    fun extract(text: String): List<ExtractedCitation> {
        val names = LawAliases.knownNames()
        val found = LinkedHashMap<String, ExtractedCitation>()
        for (m in ARTICLE.findAll(text)) {
            val before = text.substring((m.range.first - 80).coerceAtLeast(0), m.range.first)
            val (law, level) = lawBefore(before, names) ?: continue
            val main = m.groupValues[1]
            val branch = m.groupValues[2].takeIf { it.isNotEmpty() }
            val article = "제${main}조" + (branch?.let { "의$it" } ?: "")
            val lawFull = law + (level?.let { " $it" } ?: "")
            val query = "$lawFull $article"
            val shown = text.substring(m.range.first, m.range.last + 1).replace(Regex("""\s+"""), "")
            val prev = found[query]
            found[query] = ExtractedCitation(
                lawName = lawFull,
                article = article,
                query = query,
                text = prev?.text ?: "$lawFull $shown",
                count = (prev?.count ?: 0) + 1,
            )
        }
        return found.values.toList()
    }

    /** 조 번호 바로 앞(공백·시행령 표기 허용)에 이어지는 법령명과 시행령/시행규칙 여부 */
    private fun lawBefore(before: String, names: List<String>): Pair<String, String?>? {
        val trimmed = before.trimEnd(' ', '\u00a0', '\t', '\n', '\r')
        QUOTED.find(trimmed)?.let { return it.groupValues[1].trim() to it.groupValues[2].ifEmpty { null } }
        val level = SUFFIX.find(trimmed)?.groupValues?.get(1)
        val head = if (level != null) trimmed.replace(SUFFIX, "") else trimmed
        val name = names.firstOrNull { head.endsWith(it) } ?: return null
        // 긴 이름부터 비교하므로 "상호저축은행법"이 "은행법"으로 잘못 읽히지 않는다.
        return name to level
    }
}
