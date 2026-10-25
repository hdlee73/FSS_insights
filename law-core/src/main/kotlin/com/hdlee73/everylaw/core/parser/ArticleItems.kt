package com.hdlee73.everylaw.core.parser

import com.hdlee73.everylaw.core.model.ArticleItem
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.ParsedArticle

/**
 * 조문 본문 텍스트를 항(①)·호(1.)·목(가.) 단위로 나눈다.
 * 줄 맨 앞의 번호 표시로 단계를 구분하고, 표시가 없는 줄은 앞 항목의 이어지는 글로 붙인다.
 */
object ArticleItems {
    private val HEADER = Regex("""^\s*(제\s*\d+\s*조(?:\s*의\s*\d+)?(?:\s*\([^)]*\))?)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val SUBPARAGRAPH = Regex("""^(\d+(?:의\d+)?)\.\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val ITEM = Regex("""^([가-힣])\.\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** ①~⑳, ㉑~㉟, ㊱~㊿ → 1..50, 아니면 null */
    fun circledNumber(c: Char): Int? = when (c.code) {
        in 0x2460..0x2473 -> c.code - 0x2460 + 1
        in 0x3251..0x325F -> c.code - 0x3251 + 21
        in 0x32B1..0x32BF -> c.code - 0x32B1 + 36
        else -> null
    }

    fun parse(content: String): ParsedArticle {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var header = ""
        if (lines.isNotEmpty()) {
            HEADER.matchEntire(lines[0])?.let { m ->
                header = m.groupValues[1]
                val rest = m.groupValues[2].trim()
                if (rest.isEmpty()) lines.removeAt(0) else lines[0] = rest
            }
        }

        val lead = StringBuilder()
        val roots = mutableListOf<MutableItem>()
        var para: MutableItem? = null
        var sub: MutableItem? = null
        var last: MutableItem? = null

        fun cont(line: String) {
            val l = last
            if (l == null) {
                if (lead.isNotEmpty()) lead.append('\n')
                lead.append(line)
            } else l.text = l.text + "\n" + line
        }

        for (line in lines) {
            val circled = circledNumber(line.first())
            if (circled != null) {
                val it = MutableItem(ItemPath(paragraph = circled), line.first().toString(), line)
                roots += it; para = it; sub = null; last = it
                continue
            }
            val s = SUBPARAGRAPH.matchEntire(line)
            if (s != null) {
                val no = s.groupValues[1]
                val base = para?.path ?: ItemPath()
                val it = MutableItem(base.copy(subparagraph = no, item = null), "$no.", line)
                (para?.children ?: roots) += it; sub = it; last = it
                continue
            }
            val m = ITEM.matchEntire(line)
            if (m != null && (sub != null || para != null)) {
                val ch = m.groupValues[1]
                val base = sub?.path ?: para!!.path
                val it = MutableItem(base.copy(item = ch), "$ch.", line)
                (sub?.children ?: para!!.children) += it; last = it
                continue
            }
            cont(line)
        }
        return ParsedArticle(header, lead.toString(), roots.map { it.freeze() })
    }

    private class MutableItem(val path: ItemPath, val label: String, var text: String) {
        val children = mutableListOf<MutableItem>()
        fun freeze(): ArticleItem = ArticleItem(path, label, text, children.map { it.freeze() })
    }

    /** 표시·PDF용: 머리말을 뺀 본문을 들여쓰기한 줄 목록. (줄 문자열, 깊이) */
    fun lines(parsed: ParsedArticle): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        if (parsed.lead.isNotBlank()) out += parsed.lead to 0
        fun walk(i: ArticleItem, depth: Int) {
            out += i.text to depth
            i.children.forEach { walk(it, depth + 1) }
        }
        parsed.items.forEach { walk(it, 0) }
        return out
    }
}
