package com.hdlee73.everylaw.core.model

/**
 * 위임 계층의 단계. 순서(ordinal)가 곧 상하위 깊이다.
 * 법률 → 시행령(대통령령) → 시행규칙(총리령·부령) → 감독규정(고시) → 시행세칙(감독원 세칙)
 */
enum class LawLevel(val label: String, val shortLabel: String) {
    LAW("법률", "법률"),
    DECREE("시행령", "시행령"),
    RULE("시행규칙", "시행규칙"),
    SUPERVISORY_REGULATION("감독규정", "감독규정"),
    ENFORCEMENT_DETAIL("시행세칙", "세칙");

    val depth: Int get() = ordinal
}

/**
 * "제12조", "제10조의2", "제2-3조"(행정규칙식 편장 번호) 같은 조문 번호.
 * [main]은 "12" 또는 "2-3"처럼 하이픈 포함 문자열, [branch]는 "의N" 가지번호.
 */
data class ArticleNo(val main: String, val branch: Int? = null) {
    val display: String get() = "제${main}조" + (branch?.let { "의$it" } ?: "")

    /** lawService.do 의 JO 파라미터 형식(조번호 4자리 + 가지번호 2자리). 하이픈 번호는 지원하지 않는다. */
    val joParam: String?
        get() = main.toIntOrNull()?.let { "%04d%02d".format(it, branch ?: 0) }

    override fun toString(): String = display

    companion object {
        private val PATTERN = Regex("""제?\s*(\d+(?:-\d+)*)\s*조(?:\s*의\s*(\d+))?""")

        /** API는 조번호를 "0420"처럼 0을 채워 주기도 한다. 본문 인용("제420조")과 맞도록 "420"으로 통일한다. */
        fun normalizeMain(raw: String): String =
            raw.trim().split('-').joinToString("-") { it.trimStart('0').ifEmpty { "0" } }

        fun of(main: String, branch: Int? = null) = ArticleNo(normalizeMain(main), branch)

        fun parse(text: String): ArticleNo? = PATTERN.find(text)?.let {
            of(it.groupValues[1], it.groupValues[2].toIntOrNull())
        }
    }
}

/**
 * 조 아래 항(①)·호(1.)·목(가.)의 위치. 해당 단계가 없으면 null.
 * [subparagraph]는 호 번호("3", "2의2"), [item]은 목 글자("가").
 */
data class ItemPath(val paragraph: Int? = null, val subparagraph: String? = null, val item: String? = null) {
    val isEmpty: Boolean get() = paragraph == null && subparagraph == null && item == null

    /** 같은 위치끼리 같은 키. 그룹·UI 상태 식별용 */
    val key: String
        get() = listOfNotNull(paragraph?.let { "h$it" }, subparagraph?.let { "o$it" }, item?.let { "m$it" }).joinToString("/")

    /** "제2항 제3호 가목" */
    val display: String
        get() = listOfNotNull(paragraph?.let { "제${it}항" }, subparagraph?.let { "제${it}호" }, item?.let { "${it}목" }).joinToString(" ")

    /** 이 위치가 [prefix] 아래(또는 같은 위치)인지 */
    fun startsWith(prefix: ItemPath): Boolean =
        (prefix.paragraph == null || prefix.paragraph == paragraph) &&
            (prefix.subparagraph == null || prefix.subparagraph == subparagraph) &&
            (prefix.item == null || prefix.item == item)

    /** 한 단계 위. 더 올라갈 곳이 없으면 빈 위치 */
    fun parent(): ItemPath = when {
        item != null -> copy(item = null)
        subparagraph != null -> copy(subparagraph = null)
        else -> ItemPath()
    }
}

/** 조문 안의 항·호·목 한 줄. [text]는 그 줄 자신의 글(번호 포함)이고, 하위 단계는 [children]. */
data class ArticleItem(
    val path: ItemPath,
    val label: String,
    val text: String,
    val children: List<ArticleItem> = emptyList(),
) {
    fun flatten(): List<ArticleItem> = listOf(this) + children.flatMap { it.flatten() }
}

/** 조문 본문을 나눈 결과. [header]는 "제9조(정의)", [lead]는 항 앞의 본문. */
data class ParsedArticle(val header: String, val lead: String, val items: List<ArticleItem>) {
    fun find(path: ItemPath): ArticleItem? = items.flatMap { it.flatten() }.firstOrNull { it.path == path }
    fun allPaths(): Set<ItemPath> = items.flatMap { it.flatten() }.map { it.path }.toSet()
}

/** 하나의 조문(또는 행정규칙 조항). */
data class Article(
    val number: ArticleNo?,
    val title: String,
    val content: String,
) {
    /** "제85조(불건전 영업행위의 금지)" — 본문 머리말이 있으면 그것, 없으면 조 번호와 제목을 이어 만든다. */
    val fullTitle: String
        get() = parsed.header.ifBlank {
            val t = title.trim()
            (number?.display.orEmpty()) + if (t.isEmpty() || t.startsWith("(")) t else "($t)"
        }

    val heading: String
        get() = listOfNotNull(number?.display, title.takeIf { it.isNotBlank() }).joinToString(" ")

    /** 본문을 머리말·항·호·목으로 나눈 것(필요할 때 한 번만 계산) */
    val parsed: ParsedArticle by lazy(LazyThreadSafetyMode.NONE) { com.hdlee73.everylaw.core.parser.ArticleItems.parse(content) }

    /** 화면·PDF에 보여 줄 본문: 머리말("제9조(정의)")은 제목에 이미 있으므로 빼고, 항·호·목은 들여쓴다. */
    val body: String by lazy(LazyThreadSafetyMode.NONE) {
        com.hdlee73.everylaw.core.parser.ArticleItems.lines(parsed)
            .joinToString("\n") { (t, depth) -> "  ".repeat(depth) + t }
            .ifBlank { content }
    }
}

/**
 * 위임 트리의 노드.
 * @param id 트리 안에서 유일한 키(UI 펼침 상태 저장용)
 * @param sourceName 법령/행정규칙 이름 (예: "자본시장과 금융투자업에 관한 법률 시행령")
 * @param issuer 소관·발령 기관 (행정규칙에서 주로 채워짐)
 */
data class LawNode(
    val id: String,
    val level: LawLevel,
    val sourceName: String,
    val article: Article,
    val issuer: String? = null,
    val children: List<LawNode> = emptyList(),
    /** 법률 조문 바로 아래 노드일 때, 그 노드가 위임을 받는 법률 조문의 항·호·목 위치(조 전체면 빈 위치) */
    val anchor: ItemPath? = null,
) {
    /** 자신을 포함한 모든 하위 노드를 깊이 우선으로 펼친 목록. */
    fun flatten(): List<LawNode> = listOf(this) + children.flatMap { it.flatten() }

    fun find(nodeId: String): LawNode? =
        if (id == nodeId) this else children.firstNotNullOfOrNull { it.find(nodeId) }

    /** 루트에서 [nodeId]까지의 경로. 없으면 빈 목록. */
    fun pathTo(nodeId: String): List<LawNode> {
        if (id == nodeId) return listOf(this)
        for (child in children) {
            val sub = child.pathTo(nodeId)
            if (sub.isNotEmpty()) return listOf(this) + sub
        }
        return emptyList()
    }

    val descendantCount: Int get() = children.sumOf { 1 + it.descendantCount }
}

/** 사용자가 입력한 검색어를 파싱한 결과. */
data class LawQuery(
    val raw: String,
    val lawName: String,
    val article: ArticleNo,
    /** 항·호·목까지 지정했을 때의 위치(없으면 빈 위치 = 조 전체) */
    val path: ItemPath = ItemPath(),
) {
    /** "제86조 제2항 제3호 가목" */
    val target: String get() = (article.display + " " + path.display).trim()
}

/** 법령 검색 결과 한 건 (lawSearch.do target=law). */
data class LawSummary(
    val id: String,
    val mst: String,
    val name: String,
    val abbreviation: String?,
    val kind: String?,
    val effectiveDate: String?,
)

/** 행정규칙 검색 결과 한 건 (lawSearch.do target=admrul). */
data class AdminRuleSummary(
    val serialNo: String,
    val name: String,
    val kind: String?,
    val agency: String?,
)

/** 행정규칙 본문 (lawService.do target=admrul). */
data class AdminRuleDocument(
    val serialNo: String,
    val name: String,
    val agency: String?,
    val articles: List<Article>,
)

/** 위임조문 3단비교의 한 줄: 법률 조문과 그 조문이 위임한 시행령·시행규칙 조문들. */
data class ThreeTierRow(
    val law: Article,
    val decrees: List<Article>,
    val rules: List<Article>,
)

data class ThreeTierComparison(
    val lawName: String,
    val decreeName: String?,
    val ruleName: String?,
    val rows: List<ThreeTierRow>,
)

/** 검색 한 번의 최종 결과. */
data class DelegationTree(
    val query: LawQuery,
    val law: LawSummary,
    val root: LawNode,
    /** 조회 기준 시행일(yyyyMMdd). null 이면 현행. */
    val asOf: String? = null,
    /** 실제로 조회에 쓰인 법률 버전 (시행일 기준 조회일 때) */
    val version: LawVersion? = null,
    /** 해당 조문을 참조조문으로 다룬 판례. */
    val precedents: List<PrecedentSummary> = emptyList(),
    /** 데모 데이터 여부 (API 키가 없을 때 true). */
    val isSample: Boolean = false,
    /** 일부 단계 조회 실패 등 사용자에게 알릴 참고 메시지. */
    val warnings: List<String> = emptyList(),
    /** 판례를 어떤 기준으로 골랐는지 화면에 보여 줄 안내 */
    val precedentBasis: String = "",
)

/** 판례 검색 결과 한 건 (lawSearch.do target=prec). */
data class PrecedentSummary(
    val serialNo: String,
    val caseName: String,
    val caseNumber: String?,
    val decisionDate: String?,
    val court: String?,
    val caseType: String?,
    /** 관련도 판정 근거("참조조문에 인용" 등). 검색 결과 그대로면 null */
    val matchNote: String? = null,
)

/** 판례 본문 (lawService.do target=prec). */
data class PrecedentDetail(
    val summary: PrecedentSummary,
    val holdings: String,
    val gist: String,
    val referencedArticles: String,
    val referencedCases: String,
    val fullText: String,
)

/** 조문 제목·본문 키워드 검색에서 찾은 조문 한 건. */
data class ArticleHit(
    val law: LawSummary,
    val article: Article,
    val inTitle: Boolean,
    val snippet: String,
)

/** 키워드 통합 검색 결과. */
data class KeywordSearchResult(
    val keyword: String,
    val laws: List<LawSummary>,
    val adminRules: List<AdminRuleSummary>,
    val precedents: List<PrecedentSummary>,
    val isSample: Boolean = false,
    /** 일부 출처가 실패했을 때의 구체적인 원인 (예: "법령: DrfFormatException: …") */
    val warnings: List<String> = emptyList(),
    /** 조문 제목·본문에서 찾은 조문 */
    val articleHits: List<ArticleHit> = emptyList(),
    /** "법령명 + 키워드"로 범위를 좁힌 경우 그 법령 */
    val scopeLaw: LawSummary? = null,
)

/**
 * 법령의 시행일 기준 버전 (lawSearch.do target=eflaw).
 * @param status 현행 / 연혁 / 시행예정
 */
data class LawVersion(
    val mst: String,
    val name: String,
    val effectiveDate: String,
    val promulgationDate: String?,
    val revisionType: String?,
    val status: String?,
) {
    /** 20260101 → 2026.01.01 */
    val effectiveDateLabel: String get() = formatYmd(effectiveDate)
}

fun formatYmd(ymd: String?): String =
    if (ymd != null && ymd.length == 8 && ymd.all(Char::isDigit)) "${ymd.substring(0, 4)}.${ymd.substring(4, 6)}.${ymd.substring(6)}"
    else ymd.orEmpty()

/** PDF에 덧붙이려고 따로 내려받은 자료: 판례 본문과, 본문이 인용한 같은 법의 다른 조문. */
data class PdfExtras(
    val precedents: List<PrecedentDetail> = emptyList(),
    val relatedArticles: List<Article> = emptyList(),
)
