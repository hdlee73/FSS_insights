package io.github.hdlee73.financenewsradar.data

import java.time.LocalDate
import java.util.Locale

/**
 * 조문 참조. "자본시장법 제9조 제5항 제2호 가목" 같은 입력을 나눈 결과.
 * [lawName]은 사용자가 입력한 법령명(약칭일 수 있음)이다.
 */
data class ArticleRef(
    val lawName: String,
    val article: Int,
    /** 제10조의2의 "2". 가지번호가 없으면 0. */
    val articleBranch: Int = 0,
    val paragraph: Int? = null,
    val item: Int? = null,
    /** 제1호의2의 "2". 없으면 0. */
    val itemBranch: Int = 0,
    /** 목: 가, 나, 다 … */
    val subItem: String? = null
) {
    /** 법령 서비스의 조문 지정값(JO): 조 4자리 + 가지번호 2자리. 제3조 → "000300", 제10조의2 → "001002". */
    val joCode: String get() = String.format(Locale.ROOT, "%04d%02d", article, articleBranch)
}

/** 조문 입력 해석, 약칭 변환, 보고서·공문에 쓰는 인용 문구 만들기. 네트워크와 무관한 순수 로직. */
object LawCitation {

    private val REF = Regex(
        """^(.+?)\s*제?\s*(\d+)\s*조(?:\s*의\s*(\d+))?""" +
            """(?:\s*제?\s*(\d+)\s*항)?""" +
            """(?:\s*제?\s*(\d+)\s*호(?:\s*의\s*(\d+))?)?""" +
            """(?:\s*([가-힣])\s*목)?\s*$"""
    )
    private val SPACES = Regex("""\s+""")

    /**
     * 입력을 조문 참조로 해석한다. 법령명이나 조 번호가 없으면 null.
     * 지원 예: "자본시장법 제9조 제5항", "자본시장법 9조5항", "금소법 제17조의2 제3항 제1호 가목",
     * "「전자금융거래법」 제2조", "자본시장법 제9조 ⑤".
     */
    fun parse(input: String): ArticleRef? {
        val text = normalize(input)
        if (text.isEmpty()) return null
        val m = REF.matchEntire(text) ?: return null
        val g = m.groupValues
        val lawName = g[1].trim()
        // "제9조"처럼 법령명이 없는 입력이 "제"를 법령명으로 읽히지 않도록 막는다.
        if (lawName.length < 2) return null
        val item = g[5].toIntOrNull()
        val sub = g[7].ifEmpty { null }
        if (sub != null && item == null) return null
        return ArticleRef(
            lawName = lawName,
            article = g[2].toInt(),
            articleBranch = g[3].toIntOrNull() ?: 0,
            paragraph = g[4].toIntOrNull(),
            item = item,
            itemBranch = g[6].toIntOrNull() ?: 0,
            subItem = sub
        )
    }

    private fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            when {
                ch in '①'..'⑳' -> sb.append("제").append(ch - '①' + 1).append("항")
                ch == '「' || ch == '」' || ch == '『' || ch == '』' -> Unit
                else -> sb.append(ch)
            }
        }
        return SPACES.replace(sb.toString(), " ").trim().trimEnd('.', ',')
    }

    /** "제9조제5항제2호가목"처럼 조·항·호·목을 띄어쓰기 없이 붙인 부분. */
    fun articlePart(ref: ArticleRef): String = buildString {
        append("제").append(ref.article).append("조")
        if (ref.articleBranch > 0) append("의").append(ref.articleBranch)
        ref.paragraph?.let { append("제").append(it).append("항") }
        ref.item?.let {
            append("제").append(it).append("호")
            if (ref.itemBranch > 0) append("의").append(ref.itemBranch)
        }
        ref.subItem?.let { append(it).append("목") }
    }

    /** 보고서·공문용 인용: 「자본시장과 금융투자업에 관한 법률」 제9조제5항 */
    fun format(ref: ArticleRef, fullLawName: String = resolveLawName(ref.lawName)): String =
        "「$fullLawName」 ${articlePart(ref)}"

    /** 복사할 때 기준 시점을 함께 남기는 인용: 「…」 제9조제5항 (2025. 3. 1. 시행) */
    fun formatWithEffectiveDate(
        ref: ArticleRef,
        effectiveDate: LocalDate,
        fullLawName: String = resolveLawName(ref.lawName)
    ): String = "${format(ref, fullLawName)} (${effectiveDate.year}. ${effectiveDate.monthValue}. ${effectiveDate.dayOfMonth}. 시행)"

    /**
     * 약칭을 정식 법령명으로. 사전에 없으면 입력을 그대로 돌려준다(공백만 정리).
     * 사전은 자주 쓰는 약칭의 대비용이며, 법제처의 약칭 목록을 받을 수 있으면 그쪽을 우선한다.
     */
    fun resolveLawName(name: String): String {
        val trimmed = SPACES.replace(name.trim(), " ")
        return ABBREVIATIONS[trimmed.replace(" ", "")] ?: trimmed
    }

    val ABBREVIATIONS: Map<String, String> = mapOf(
        "자본시장법" to "자본시장과 금융투자업에 관한 법률",
        "금소법" to "금융소비자 보호에 관한 법률",
        "금융소비자보호법" to "금융소비자 보호에 관한 법률",
        "전금법" to "전자금융거래법",
        "금융위법" to "금융위원회의 설치 등에 관한 법률",
        "금융위설치법" to "금융위원회의 설치 등에 관한 법률",
        "지배구조법" to "금융회사의 지배구조에 관한 법률",
        "특금법" to "특정 금융거래정보의 보고 및 이용 등에 관한 법률",
        "여전법" to "여신전문금융업법",
        "대부업법" to "대부업 등의 등록 및 금융이용자 보호에 관한 법률",
        "신용정보법" to "신용정보의 이용 및 보호에 관한 법률",
        "외감법" to "주식회사 등의 외부감사에 관한 법률",
        "금융실명법" to "금융실명거래 및 비밀보장에 관한 법률",
        "유사수신법" to "유사수신행위의 규제에 관한 법률",
        "온투법" to "온라인투자연계금융업 및 이용자 보호에 관한 법률",
        "가상자산이용자보호법" to "가상자산 이용자 보호 등에 관한 법률",
        "공정거래법" to "독점규제 및 공정거래에 관한 법률"
    )
}
