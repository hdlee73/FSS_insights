package io.github.hdlee73.financenewsradar.data

/**
 * Converts a user-facing Boolean expression into a small set of provider queries.
 * OR branches are requested independently and merged, which avoids relying on
 * provider-specific Boolean syntax and improves result coverage.
 */
data class SearchPlan(
    val providerQueries: List<String>,
    val terms: List<String>,
    val usesBooleanOperators: Boolean
)

object SearchQueryParser {
    private const val MAX_BRANCHES = 12

    fun parse(raw: String): SearchPlan {
        val tokens = tokenize(raw.trim())
        require(tokens.isNotEmpty()) { "검색어를 입력해 주세요." }
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
            usesBooleanOperators = tokens.any { it is Token.And || it is Token.Or || it is Token.LeftParen }
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

    private fun tokenize(raw: String): List<Token> {
        val result = mutableListOf<Token>()
        var index = 0
        while (index < raw.length) {
            when {
                raw[index].isWhitespace() -> index++
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
