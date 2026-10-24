package io.github.hdlee73.financenewsradar.law.tree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import io.github.hdlee73.financenewsradar.law.CitationText
import io.github.hdlee73.financenewsradar.law.citationContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hdlee73.everylaw.core.domain.ItemAttachment
import com.hdlee73.everylaw.core.model.ArticleItem
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.model.formatYmd
import io.github.hdlee73.financenewsradar.law.search.SearchUiState
import io.github.hdlee73.financenewsradar.law.LawBodyStyle
import io.github.hdlee73.financenewsradar.law.LevelColors

/**
 * 법조문과 하위 규정을 계층으로 보여주는 뷰.
 * 화면 너비가 넓으면(폴더블 펼침·태블릿, 600dp 이상) 좌우 분할, 아니면 아코디언.
 */
@Composable
fun LawTreeView(
    state: SearchUiState.TreeSuccess,
    isWide: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenPrecedent: (PrecedentSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isWide) SplitTreeView(state, header, actions, onToggle, onSelect, onOpenPrecedent, modifier)
    else AccordionTreeView(state, header, actions, onToggle, onOpenPrecedent, modifier)
}

// ------------------------------------------------------------------ 아코디언 (폰)

private data class VisibleNode(val node: LawNode, val depth: Int)

private fun visibleNodes(root: LawNode, expanded: Set<String>): List<VisibleNode> {
    val out = mutableListOf<VisibleNode>()
    fun walk(n: LawNode, d: Int) {
        out += VisibleNode(n, d)
        if (n.id in expanded) n.children.forEach { walk(it, d + 1) }
    }
    walk(root, 0)
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccordionTreeView(
    state: SearchUiState.TreeSuccess,
    header: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
    onToggle: (String) -> Unit,
    onOpenPrecedent: (PrecedentSummary) -> Unit,
    modifier: Modifier,
) {
    val root = state.tree.root
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { Column { header() } }
        stickyHeader(key = "actions") { actions() }
        item(key = "article") {
            ArticleCard {
                ArticleBody(root, state.expanded, onToggle) { nodes ->
                    nodes.forEachIndexed { i, n -> StepNode(root, n, i == nodes.lastIndex, state.expanded, onToggle) }
                }
            }
        }
        precedentItems(state.tree, onOpenPrecedent)
    }
}

/** 법률 조문 카드(흰 카드). 제목은 위의 남색 머리 카드에 있고, 여기에는 본문(항·호·목)과 그 아래 위임 규정이 들어간다. */
@Composable
private fun ArticleCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** 찾는 대상이 특정 항·호여도 항상 조 제목("제112조(의결권 등)")을 맨 앞에 보여 준다. */
@Composable
private fun ArticleTitle(article: com.hdlee73.everylaw.core.model.Article) {
    val title = article.parsed.header.ifBlank {
        val t = article.title.trim()
        val shown = if (t.isEmpty() || t.startsWith("(")) t else "($t)"
        (article.number?.display.orEmpty() + shown)
    }
    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun groupId(path: ItemPath) = "grp:" + path.key

/**
 * 조문 본문을 항·호·목 단위로 그리고, 각 위치를 위임 근거로 삼는 하위 규정을 그 줄 바로 아래에 접이식 묶음으로 보여 준다.
 * 위치를 알 수 없는 하위 규정은 맨 아래 "조 전체"에 둔다.
 */
@Composable
private fun ArticleBody(
    root: LawNode,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    renderNodes: @Composable (List<LawNode>) -> Unit,
) {
    val ctx = citationContext(root, root)
    val parsed = root.article.parsed
    val attached = remember(root) { ItemAttachment.attach(root.article, root.children) }

    @Composable
    fun Group(path: ItemPath, depth: Int) {
        val nodes = attached[path] ?: return
        DelegationGroup(
            nodes = nodes,
            title = if (path.isEmpty) "조 전체에 연결된 하위 규정" else "${path.display} 하위 규정",
            expanded = groupId(path) in expanded,
            onToggle = { onToggle(groupId(path)) },
            depth = depth,
            content = { renderNodes(nodes) },
        )
    }

    @Composable
    fun Item(item: ArticleItem, depth: Int) {
        CitationText(
            item.text, ctx, style = LawBodyStyle,
            modifier = Modifier.padding(start = (depth * 14).dp), selectable = true,
        )
        Group(item.path, depth)
        item.children.forEach { Item(it, depth + 1) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArticleTitle(root.article)
        if (parsed.lead.isNotBlank()) CitationText(parsed.lead, ctx, style = LawBodyStyle, selectable = true)
        if (parsed.items.isEmpty() && parsed.lead.isBlank()) CitationText(root.article.content, ctx, style = LawBodyStyle, selectable = true)
        parsed.items.forEach { Item(it, 0) }
        Group(ItemPath(), 0)
    }
}

/** 접었다 펴는 하위 규정 묶음. 접은 상태에서는 몇 건이 어느 단계에 있는지 한 줄로 알려 준다. */
@Composable
private fun DelegationGroup(
    nodes: List<LawNode>,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    depth: Int,
    content: @Composable () -> Unit,
) {
    val counts = nodes.flatMap { it.flatten() }.groupingBy { it.level }.eachCount().toSortedMap()
    val summary = counts.entries.joinToString(" · ") { "${it.key.label} ${it.value}건" }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "group-chevron")
    Column(
        Modifier.padding(start = (depth * 14).dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            onClick = onToggle,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "펼침" else "접힘" },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** 위임 단계 번호(법률 1 · 시행령 2 · 시행규칙 3 · 감독규정 4 · 시행세칙 5)를 담은 동그라미 */
@Composable
fun StepDot(level: LawLevel, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val tone = LevelColors.current[level]
    Box(Modifier.size(size).clip(CircleShape).background(tone.accent), contentAlignment = Alignment.Center) {
        Text("${level.ordinal + 1}", color = tone.onAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * 하위 규정 한 건을 번호 붙은 단계로 그린다. 왼쪽에 단계 번호와 이어지는 세로선, 오른쪽에 제목·본문.
 * 펼치면 본문과 그 아래 단계들이 이어진다.
 */
@Composable
private fun StepNode(root: LawNode, node: LawNode, isLast: Boolean, expanded: Set<String>, onToggle: (String) -> Unit) {
    val tone = LevelColors.current[node.level]
    val open = node.id in expanded
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "step-chevron")
    val labelColor = if (node.level == LawLevel.SUPERVISORY_REGULATION) tone.onContainer else tone.accent
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepDot(node.level)
            Box(
                Modifier.width(2.dp).weight(1f)
                    .background(if (isLast) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 24.dp)
                    .clickable(role = Role.Button) { onToggle(node.id) }
                    .semantics { stateDescription = if (open) "펼침" else "접힘" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(node.level.label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                Text(
                    node.article.heading.ifBlank { node.sourceName },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = if (open) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node.children.isNotEmpty()) {
                    Text("하위 ${node.descendantCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp).rotate(rotation))
            }
            Text(node.sourceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) {
                CitationText(node.article.body, citationContext(root, node), style = LawBodyStyle, selectable = true)
                Column(Modifier.padding(top = 8.dp)) {
                    node.children.forEachIndexed { i, c -> StepNode(root, c, i == node.children.lastIndex, expanded, onToggle) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 분할 (폴더블)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitTreeView(
    state: SearchUiState.TreeSuccess,
    header: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenPrecedent: (PrecedentSummary) -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 왼쪽: 상위 조문 + 위임 트리
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") { Column { header() } }
            stickyHeader(key = "actions") { actions() }
            item(key = "article") {
                val root = state.tree.root
                ArticleCard {
                    ArticleBody(root, state.expanded, onToggle) { nodes ->
                        nodes.forEach { n -> SplitNodeTree(n, 0, state.selectedId, onSelect) }
                    }
                }
            }
            item(key = "prec-entry") {
                val selected = state.selectedId == SearchUiState.PRECEDENTS_PANE
                Surface(
                    onClick = { onSelect(SearchUiState.PRECEDENTS_PANE) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Gavel, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("관련 판례 ${state.tree.precedents.size}건", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
        // 오른쪽: 선택한 하위 규정 본문 (또는 판례 목록)
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            if (state.selectedId == SearchUiState.PRECEDENTS_PANE) {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    precedentItems(state.tree, onOpenPrecedent)
                }
            } else {
                val node = state.tree.root.find(state.selectedId) ?: state.tree.root
                NodeDetail(state.tree, node, onSelect)
            }
        }
    }
}

private fun depthMap(root: LawNode): Map<String, Int> {
    val m = mutableMapOf<String, Int>()
    fun walk(n: LawNode, d: Int) {
        m[n.id] = d
        n.children.forEach { walk(it, d + 1) }
    }
    walk(root, 0)
    return m
}

/** 분할 화면 왼쪽: 위임 규정을 선택 가능한 줄로, 하위 규정까지 이어서 그린다. */
@Composable
private fun SplitNodeTree(node: LawNode, depth: Int, selectedId: String, onSelect: (String) -> Unit) {
    SplitTreeRow(node, depth, node.id == selectedId) { onSelect(node.id) }
    node.children.forEach { SplitNodeTree(it, depth + 1, selectedId, onSelect) }
}

@Composable
private fun SplitTreeRow(node: LawNode, depth: Int, selected: Boolean, onClick: () -> Unit) {
    val tone = LevelColors.current[node.level]
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = tone.container,
        contentColor = tone.onContainer,
        border = if (selected) BorderStroke(2.dp, tone.accent) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
            .semantics { if (selected) stateDescription = "선택됨" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepDot(node.level)
            Column(Modifier.weight(1f)) {
                Text(
                    node.article.heading.ifBlank { node.sourceName },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${node.level.label} · ${node.sourceName}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeDetail(tree: DelegationTree, node: LawNode, onSelect: (String) -> Unit) {
    val path = tree.root.pathTo(node.id)
    val parent = path.dropLast(1).lastOrNull()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            path.joinToString(" › ") { it.level.shortLabel + " " + (it.article.number?.display ?: "") },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelBadge(node.level, large = true)
            node.issuer?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
        Text(node.sourceName, style = MaterialTheme.typography.titleMedium)
        Text(node.article.heading, style = MaterialTheme.typography.headlineSmall)
        CitationText(node.article.body, citationContext(tree.root, node), style = LawBodyStyle, selectable = true)

        if (node.children.isNotEmpty()) {
            HorizontalDivider()
            Text("이 조문이 위임한 하위 규정", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.children.forEach { c ->
                    AssistChip(
                        onClick = { onSelect(c.id) },
                        label = { Text("${c.level.shortLabel} ${c.article.number?.display ?: c.article.title}") },
                    )
                }
            }
        }
        if (parent != null) {
            TextButton(onClick = { onSelect(parent.id) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("상위 ${parent.level.label} ${parent.article.number?.display ?: ""} 보기")
            }
        }
    }
}

// ------------------------------------------------------------------ 공통

@Composable
fun LevelBadge(level: LawLevel, large: Boolean = false) {
    val tone = LevelColors.current[level]
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone.accent)
            .padding(horizontal = if (large) 12.dp else 10.dp, vertical = if (large) 4.dp else 3.dp)
            .semantics { contentDescription = level.label },
    ) {
        Text(
            if (large) level.label else level.shortLabel,
            color = tone.onAccent,
            style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun LazyListScope.precedentItems(tree: DelegationTree, onOpen: (PrecedentSummary) -> Unit) {
    item(key = "prec-title") {
        Row(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("관련 판례", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("${tree.precedents.size}건", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (tree.precedentBasis.isNotBlank()) {
        item(key = "prec-basis") {
            Text(tree.precedentBasis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (tree.precedents.isEmpty()) {
        item(key = "prec-empty") {
            Text("이 조문을 인용한 판례를 찾지 못했어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(tree.precedents, key = { "prec-" + it.serialNo + it.caseNumber }) { p -> PrecedentRow(p, onClick = { onOpen(p) }) }
}

@Composable
fun PrecedentRow(p: PrecedentSummary, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            p.matchNote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
            }
            Text(p.caseName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(p.court, p.caseNumber, p.decisionDate?.let(::formatYmd), p.caseType).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
