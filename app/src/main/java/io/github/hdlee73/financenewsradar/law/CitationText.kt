package io.github.hdlee73.financenewsradar.law

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.hdlee73.everylaw.core.parser.CitationLinker

/** 본문 속 조문 인용을 눌렀을 때 그 조문을 검색하는 동작. 앱 루트에서 제공한다. */
val LocalCitationHandler = compositionLocalOf<(String) -> Unit> { {} }

/** 결과 안 찾기·숨기기 상태. 트리 화면이 제공하고, 본문·제목 그리기와 체크박스가 읽는다. */
data class TreeViewOptions(
    val highlight: String = "",
    val selectMode: Boolean = false,
    val hidden: Set<String> = emptySet(),
    /** 하위 법규를 위임한 항·호 바로 아래에 펼쳐 보여 주는 방식 */
    val inline: Boolean = false,
    val onHide: (nodeKey: String, hide: Boolean) -> Unit = { _, _ -> },
)

val LocalTreeViewOptions = compositionLocalOf { TreeViewOptions() }

/** [query]와 맞는 글자 범위. 조문번호 검색("제12조")이면 그 번호 표기를 찾는다. */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val needle = com.hdlee73.everylaw.core.domain.TreeFilter.articleNo(q)?.display ?: q
    val out = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val i = text.indexOf(needle, from, ignoreCase = true)
        if (i < 0) break
        out += i until i + needle.length
        from = i + needle.length
    }
    return out
}

/** 제목 같은 짧은 글에 검색어 강조를 입힌다. */
@Composable
fun highlighted(text: String, query: String): AnnotatedString {
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    return remember(text, query, bg) {
        buildAnnotatedString {
            append(text)
            highlightRanges(text, query).forEach { addStyle(SpanStyle(background = bg), it.first, it.last + 1) }
        }
    }
}

@Composable
fun ProvideCitationHandler(onCite: (String) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCitationHandler provides onCite, content = content)
}

/** 조문 본문. "제12조", "법 제11조", 「다른 법」 제N조 같은 인용이 눌러지는 링크가 된다. */
@Composable
fun CitationText(
    text: String,
    ctx: CitationLinker.Context,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectable: Boolean = false,
) {
    val onCite = LocalCitationHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val highlight = LocalTreeViewOptions.current.highlight
    val hlBg = MaterialTheme.colorScheme.tertiaryContainer
    val annotated = remember(text, ctx, linkColor, onCite, highlight, hlBg) {
        val cites = CitationLinker.find(text, ctx)
        buildAnnotatedString {
            var pos = 0
            cites.forEach { c ->
                append(text.substring(pos, c.start))
                withLink(
                    LinkAnnotation.Clickable(
                        tag = c.query,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ) { onCite(c.query) }
                ) { append(text.substring(c.start, c.end)) }
                pos = c.end
            }
            append(text.substring(pos))
            highlightRanges(text, highlight).forEach { addStyle(SpanStyle(background = hlBg), it.first, it.last + 1) }
        }
    }
    val body = @Composable { Text(annotated, style = style, modifier = modifier, maxLines = maxLines, overflow = overflow) }
    if (selectable) SelectionContainer { body() } else body()
}

/** 트리 노드의 본문 인용이 어느 법령 기준인지: 법률 이름·시행령 이름·시행규칙 이름을 트리에서 찾는다. */
fun citationContext(root: com.hdlee73.everylaw.core.model.LawNode, node: com.hdlee73.everylaw.core.model.LawNode): CitationLinker.Context {
    val all = root.flatten()
    val decree = all.firstOrNull { it.level == com.hdlee73.everylaw.core.model.LawLevel.DECREE }?.sourceName
    val rule = all.firstOrNull { it.level == com.hdlee73.everylaw.core.model.LawLevel.RULE }?.sourceName
    val own = when (node.level) {
        com.hdlee73.everylaw.core.model.LawLevel.LAW,
        com.hdlee73.everylaw.core.model.LawLevel.DECREE,
        com.hdlee73.everylaw.core.model.LawLevel.RULE -> node.sourceName
        else -> null // 감독규정·세칙의 "제2-3조"는 행정규칙 조문이라 연결하지 않는다
    }
    return CitationLinker.Context(root.sourceName, decree, rule, own)
}
