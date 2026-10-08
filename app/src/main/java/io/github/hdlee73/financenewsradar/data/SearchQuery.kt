package io.github.hdlee73.financenewsradar.data

/**
 * Converts a user-facing Boolean expression into a small set of provider queries.
 * OR branches are requested independently and merged, which avoids relying on
 * provider-specific Boolean syntax and improves result coverage.
 */
data class SearchPlan(
    val providerQueries: List<String>,
    val terms: List<String>,
    val usesBooleanOperators: Boolean,
    /** `-단어` 로 지정한 제외어. 이 단어가 제목·요약에 들어 있는 기사는 결과에서 뺀다. */
    val excludedTerms: List<String> = emptyList()
)

object SearchQueryParser {
    private const val MAX_BRANCHES = 12

    fun parse(raw: String): SearchPlan {
        val excluded = mutableListOf<String>()
        val tokens = tokenize(raw.trim(), excluded)
        require(tokens.isNotEmpty()) {
            if (excluded.isEmpty()) "검색어를 입력해 주세요." else "제외할 단어(-단어)만으로는 검색할 수 없습니다. 찾을 단어를 함께 입력해 주세요."
        }
        val parser = Parser(tokens)
        val expression = parser.parse()
        val groups = toGroups(expression)
            .map { group -> group.distinctBy { it.lowercase() } }
            .distinctBy { group -> group.joinToString("\u001F") { it.lowercase() } }
        require(groups.size <= MAX_BRANCHES) {
            "OR 조합이 너무 많습니다. OR 분기를 ${MAX_BRANCHES}개 이하로 줄여 주세요."
        }
        val queries = groups.map { group ->
            group.joinToString(" ") { term ->
                if (term.any(Char::isWhitespace)) "\"${term.replace("\"", "")}\"" else term
            }
        }
        return SearchPlan(
            providerQueries = queries,
            terms = groups.flatten().distinctBy { it.lowercase() },
            usesBooleanOperators = tokens.any { it is Token.And || it is Token.Or || it is Token.LeftParen },
            excludedTerms = excluded.distinctBy { it.lowercase() }
        )
    }

    private sealed interface Expression {
        data class Term(val value: String) : Expression
        data class And(val left: Expression, val right: Expression) : Expression
        data class Or(val left: Expression, val right: Expression) : Expression
    }

    private sealed interface Token {
        data class Term(val value: String) : Token
        data object And : Token
        data object Or : Token
        data object LeftParen : Token
        data object RightParen : Token
    }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        fun parse(): Expression {
            val result = parseOr()
            require(index == tokens.size) { "검색식의 괄호 또는 연산자 위치를 확인해 주세요." }
            return result
        }

        private fun parseOr(): Expression {
            var left = parseAnd()
            while (peek() is Token.Or) {
                index++
                left = Expression.Or(left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Expression {
            var left = parsePrimary()
            while (true) {
                when (peek()) {
                    is Token.And -> {
                        index++
                        left = Expression.And(left, parsePrimary())
                    }
                    is Token.Term, is Token.LeftParen -> {
                        // A space without an operator behaves as AND.
                        left = Expression.And(left, parsePrimary())
                    }
                    else -> return left
                }
            }
        }

        private fun parsePrimary(): Expression = when (val token = peek()) {
            is Token.Term -> {
                index++
                Expression.Term(token.value)
            }
            is Token.LeftParen -> {
                index++
                val nested = parseOr()
                require(peek() is Token.RightParen) { "닫는 괄호가 필요합니다." }
                index++
                nested
            }
            else -> throw IllegalArgumentException("AND/OR 앞뒤에 검색어가 필요합니다.")
        }

        private fun peek(): Token? = tokens.getOrNull(index)
    }

    private fun toGroups(expression: Expression): List<List<String>> = when (expression) {
        is Expression.Term -> listOf(listOf(expression.value))
        is Expression.Or -> toGroups(expression.left) + toGroups(expression.right)
        is Expression.And -> {
            val left = toGroups(expression.left)
            val right = toGroups(expression.right)
            require(left.size * right.size <= MAX_BRANCHES) {
                "OR 조합이 너무 많습니다. OR 분기를 ${MAX_BRANCHES}개 이하로 줄여 주세요."
            }
            left.flatMap { leftGroup -> right.map { rightGroup -> leftGroup + rightGroup } }
        }
    }

    /** 제목·요약([text])에 제외어가 하나라도 들어 있으면 true. */
    fun isExcluded(text: String, excludedTerms: List<String>): Boolean =
        excludedTerms.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }

    /** 단어 맨 앞의 `-`(바로 뒤에 단어나 따옴표 문구가 붙은 경우)를 제외어로 모으고 나머지를 토큰으로 만든다. */
    private fun tokenize(raw: String, excluded: MutableList<String>): List<Token> {
        val result = mutableListOf<Token>()
        var index = 0
        while (index < raw.length) {
            when {
                raw[index].isWhitespace() -> index++
                raw[index] == '-' && index + 1 < raw.length && !raw[index + 1].isWhitespace() && raw[index + 1] !in charArrayOf('(', ')', '&', '|') -> {
                    index++
                    if (raw[index] == '"' || raw[index] == '\'') {
                        val quote = raw[index++]
                        val start = index
                        while (index < raw.length && raw[index] != quote) index++
                        require(index < raw.length) { "따옴표를 닫아 주세요." }
                        val phrase = raw.substring(start, index).trim()
                        require(phrase.isNotEmpty()) { "빈 따옴표는 검색할 수 없습니다." }
                        excluded += phrase
                        index++
                    } else {
                        val start = index
                        while (index < raw.length && !raw[index].isWhitespace() && raw[index] !in charArrayOf('(', ')', '&', '|')) index++
                        excluded += raw.substring(start, index)
                    }
                }
                raw[index] == '(' -> { result += Token.LeftParen; index++ }
                raw[index] == ')' -> { result += Token.RightParen; index++ }
                raw[index] == '&' -> { result += Token.And; index++ }
                raw[index] == '|' -> { result += Token.Or; index++ }
                raw[index] == '"' || raw[index] == '\'' -> {
                    val quote = raw[index++]
                    val start = index
                    while (index < raw.length && raw[index] != quote) index++
                    require(index < raw.length) { "따옴표를 닫아 주세요." }
                    val phrase = raw.substring(start, index).trim()
                    require(phrase.isNotEmpty()) { "빈 따옴표는 검색할 수 없습니다." }
                    result += Token.Term(phrase)
                    index++
                }
                else -> {
                    val start = index
                    while (
                        index < raw.length && !raw[index].isWhitespace() &&
                        raw[index] !in charArrayOf('(', ')', '&', '|')
                    ) index++
                    val word = raw.substring(start, index)
                    result += when (word.uppercase()) {
                        "AND", "그리고" -> Token.And
                        "OR", "또는" -> Token.Or
                        else -> Token.Term(word)
                    }
                }
            }
        }
        return result
    }
}
