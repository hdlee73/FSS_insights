package io.github.hdlee73.financenewsradar.law.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.formatYmd
import io.github.hdlee73.financenewsradar.law.LawBodyStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
        },
        actions = { actions() },
    )
}

@Composable
fun <T> LoadStateBox(state: LoadState<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LoadState.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("다시 시도") }
        }
        is LoadState.Success -> content(state.data)
    }
}

/** 판례 본문: 판시사항 · 판결요지 · 참조조문 · 참조판례 · 전문 */
@Composable
fun PrecedentScreen(vm: PrecedentViewModel, serialNo: String, onBack: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(serialNo) { vm.load(serialNo) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0), topBar = { BackTopBar("판례", onBack) }) { pad ->
        Box(Modifier.padding(pad)) {
            LoadStateBox(state, onRetry = { vm.load(serialNo, force = true) }) { p ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .widthIn(max = 840.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(p.summary.caseName, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull(p.summary.court, p.summary.caseNumber, p.summary.decisionDate?.let(::formatYmd), p.summary.caseType)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Section("판시사항", p.holdings)
                    Section("판결요지", p.gist, highlight = true)
                    Section("참조조문", p.referencedArticles)
                    Section("참조판례", p.referencedCases)
                    Section("전문", p.fullText)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String, highlight: Boolean = false) {
    if (body.isBlank()) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(body, style = LawBodyStyle) }
        }
    }
}

/** 법령/행정규칙 조문 목록. 법령의 조문을 누르면 그 조문의 위임 트리를 조회한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    vm: DocumentViewModel,
    load: DocumentViewModel.() -> Unit,
    onBack: () -> Unit,
    onOpenTree: (String) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0), topBar = { BackTopBar((state as? LoadState.Success)?.data?.title ?: "조문", onBack) }) { pad ->
        Box(Modifier.padding(pad)) {
            LoadStateBox(state, onRetry = { vm.load() }) { doc ->
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    doc.subtitle?.let { item { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    if (doc.law != null) item {
                        Text("조문을 누르면 하위 규정까지 위임 트리로 보여드려요.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(doc.articles, key = { it.heading + it.content.hashCode() }) { a ->
                        ArticleCard(a, doc.law?.name ?: doc.title, onTree = doc.law?.let { law -> a.number?.let { no -> { onOpenTree("${law.name} ${no.display}") } } })
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(a: Article, docName: String, onTree: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().let { if (onTree != null) it.clickable(role = Role.Button, onClick = onTree) else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (onTree != null) Icon(Icons.Outlined.AccountTree, contentDescription = "위임 트리 보기", tint = MaterialTheme.colorScheme.primary)
            }
            io.github.hdlee73.financenewsradar.law.CitationText(
                a.content,
                com.hdlee73.everylaw.core.parser.CitationLinker.Context(docName, ownName = docName.takeIf { onTree != null }),
                style = LawBodyStyle, maxLines = 8, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 즐겨찾기 / 최근 기록 목록 (하단 탭) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryListScreen(
    title: String,
    items: List<String>,
    emptyText: String,
    isFavorites: Boolean,
    onPick: (String) -> Unit,
    onRemove: ((String) -> Unit)?,
    onClearAll: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                actions = {
                    if (onClearAll != null && items.isNotEmpty()) IconButton(onClick = onClearAll) {
                        Icon(Icons.Outlined.Delete, contentDescription = "모두 지우기")
                    }
                },
            )
        },
    ) { pad ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(items, key = { it }) { q ->
                    ListItem(
                        headlineContent = { Text(q) },
                        leadingContent = { Icon(if (isFavorites) Icons.Outlined.StarOutline else Icons.Outlined.History, null) },
                        trailingContent = onRemove?.let { remove ->
                            { IconButton(onClick = { remove(q) }) { Icon(Icons.Outlined.Delete, contentDescription = "삭제") } }
                        },
                        modifier = Modifier.clickable(role = Role.Button) { onPick(q) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
