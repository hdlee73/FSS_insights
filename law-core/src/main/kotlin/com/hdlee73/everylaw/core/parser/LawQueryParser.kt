package com.hdlee73.everylaw.core.parser

import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawQuery

/**
 * "자본시장법 제11조", "은행법 34조의2", "자본시장법 §12" 같은 자유 입력을 법령명 + 조문번호로 나눈다.
 * 약칭은 [LawAliases]로 정식 명칭으로 바꾼다(국가법령정보센터 검색은 정식 명칭 일치율이 더 높다).
 */
object LawQueryParser {

    private val ARTICLE = Regex("""(?:제\s*)?(\d+)\s*조(?:\s*의\s*(\d+))?|§\s*(\d+)(?:\s*의\s*(\d+))?""")

    private val PARAGRAPH = Regex("""\s*(?:제\s*)?(\d+)\s*항""")
    private val SUBPARAGRAPH = Regex("""\s*(?:제\s*)?(\d+(?:\s*의\s*\d+)?)\s*호""")
    private val ITEM = Regex("""\s*([가-힣])\s*목""")

    sealed interface Result {
        data class Ok(val query: LawQuery) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(input: String): Result {
        val raw = input.trim()
        if (raw.isEmpty()) return Result.Invalid("법령명과 조문을 입력해 주세요. 예) 자본시장법 제11조")

        val match = ARTICLE.find(raw)
            ?: return Result.Invalid("조문 번호를 찾지 못했어요. '제11조'처럼 입력해 주세요.")

        val main = match.groupValues[1].ifEmpty { match.groupValues[3] }
        val branch = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull()

        // 조 뒤에 이어지는 "제2항 제3호 가목"을 읽는다. 읽은 만큼은 법령명에서 뺀다.
        var pos = match.range.last + 1
        var paragraph: Int? = null
        var sub: String? = null
        var item: String? = null
        PARAGRAPH.matchAt(raw, pos)?.let { paragraph = it.groupValues[1].toInt(); pos = it.range.last + 1 }
        SUBPARAGRAPH.matchAt(raw, pos)?.let { sub = it.groupValues[1].replace(Regex("""\s+"""), ""); pos = it.range.last + 1 }
        ITEM.matchAt(raw, pos)?.let { item = it.groupValues[1]; pos = it.range.last + 1 }

        val name = (raw.substring(0, match.range.first) + " " + raw.substring(pos))
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd(',', '.', '·')
            .trim()
        if (name.isEmpty()) return Result.Invalid("법령명을 함께 입력해 주세요. 예) 자본시장법 제11조")

        return Result.Ok(
            LawQuery(
                raw = raw,
                lawName = LawAliases.resolve(name),
                article = ArticleNo.of(main, branch),
                path = ItemPath(paragraph, sub, item),
            )
        )
    }
}

/**
 * 자주 쓰는 약칭과, 금융 법률별로 금융위원회·금융감독원이 제정한 감독규정/시행세칙 이름.
 * 법령 체계도 API가 행정규칙을 돌려주지 않을 때의 보조 수단이다.
 */
object LawAliases {

    private val aliases = mapOf(
        "자본시장법" to "자본시장과 금융투자업에 관한 법률",
        "자통법" to "자본시장과 금융투자업에 관한 법률",
        "금소법" to "금융소비자 보호에 관한 법률",
        "금융소비자보호법" to "금융소비자 보호에 관한 법률",
        "전금법" to "전자금융거래법",
        "여전법" to "여신전문금융업법",
        "저축은행법" to "상호저축은행법",
        "금융지주법" to "금융지주회사법",
        "특금법" to "특정 금융거래정보의 보고 및 이용 등에 관한 법률",
        "신용정보법" to "신용정보의 이용 및 보호에 관한 법률",
        "금융실명법" to "금융실명거래 및 비밀보장에 관한 법률",
        "외감법" to "주식회사 등의 외부감사에 관한 법률",
        "개인정보보호법" to "개인정보 보호법",
    )

    /** 정식 법률명 → (감독규정, 시행세칙) 후보 이름 */
    private val supervisory = mapOf(
        "자본시장과 금융투자업에 관한 법률" to (listOf("금융투자업규정", "증권의 발행 및 공시 등에 관한 규정") to listOf("금융투자업규정시행세칙", "증권의 발행 및 공시 등에 관한 규정 시행세칙")),
        "은행법" to (listOf("은행업감독규정") to listOf("은행업감독업무시행세칙")),
        "보험업법" to (listOf("보험업감독규정") to listOf("보험업감독업무시행세칙")),
        "여신전문금융업법" to (listOf("여신전문금융업감독규정") to listOf("여신전문금융업감독업무시행세칙")),
        "전자금융거래법" to (listOf("전자금융감독규정") to listOf("전자금융감독규정시행세칙")),
        "금융지주회사법" to (listOf("금융지주회사감독규정") to listOf("금융지주회사감독규정시행세칙")),
        "상호저축은행법" to (listOf("상호저축은행업감독규정") to listOf("상호저축은행업감독업무시행세칙")),
        "금융소비자 보호에 관한 법률" to (listOf("금융소비자 보호에 관한 감독규정") to listOf("금융소비자 보호에 관한 감독규정 시행세칙")),
    )

    /** 정식 명칭과 그 약칭들 (판례 본문에서 인용 이름을 알아볼 때 쓴다) */
    fun namesFor(formal: String): List<String> =
        listOf(formal) + aliases.filterValues { it == formal }.keys

    /** 본문에서 법령명을 알아볼 때 쓰는 이름들: 약칭, 정식 명칭, 감독규정의 모법 이름. 긴 이름이 먼저 온다. */
    fun knownNames(): List<String> =
        (aliases.keys + aliases.values + supervisory.keys).distinct().sortedByDescending { it.length }

    fun isKnown(name: String): Boolean = aliases.containsKey(name) || aliases.containsKey(name.replace(" ", "")) || supervisory.containsKey(name)

    fun resolve(name: String): String {
        val compact = name.replace(" ", "")
        return aliases[name] ?: aliases[compact] ?: name
    }

    /** 키워드만으로 조문을 찾을 때 먼저 훑는 주요 금융법령(정식 명칭) */
    fun majorLaws(): List<String> = (supervisory.keys + aliases.values.filter { it != "개인정보 보호법" }).distinct()

    /** 기준일 검색에서 먼저 보는, 지금은 폐지됐거나 이름이 바뀐 금융 구법령 */
    fun historicalFinancialLaws(): List<String> = listOf(
        "증권거래법", "증권투자신탁업법", "간접투자자산운용업법", "선물거래법", "종합금융회사에 관한 법률",
        "금융산업의 구조개선에 관한 법률", "자산유동화에 관한 법률", "신탁법", "외국환거래법", "한국증권선물거래소법",
        "주식회사의 외부감사에 관한 법률", "주식회사등의외부감사에관한법률", "증권투자회사법", "부동산투자회사법", "상법",
        "신용협동조합법", "새마을금고법", "한국산업은행법", "예금자보호법", "독점규제 및 공정거래에 관한 법률",
        "한국은행법", "은행법", "보험업법", "여신전문금융업법", "상호저축은행법", "신용정보의 이용 및 보호에 관한 법률",
    )

    fun supervisoryRegulations(lawName: String): List<String> = supervisory[lawName]?.first.orEmpty()

    fun enforcementDetails(lawName: String): List<String> = supervisory[lawName]?.second.orEmpty()
}
