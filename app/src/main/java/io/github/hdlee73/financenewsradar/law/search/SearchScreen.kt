package io.github.hdlee73.financenewsradar.law.search

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hdlee73.everylaw.core.model.AdminRuleSummary
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.KeywordSearchResult
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.model.formatYmd
import com.hdlee73.everylaw.core.domain.ItemAttachment
import com.hdlee73.everylaw.core.domain.TreeFilter
import com.hdlee73.everylaw.core.domain.hiddenCount
import com.hdlee73.everylaw.core.domain.withParagraphPieces
import com.hdlee73.everylaw.core.domain.withoutHidden
import io.github.hdlee73.financenewsradar.law.LocalTreeViewOptions
import io.github.hdlee73.financenewsradar.law.TreeViewOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import io.github.hdlee73.financenewsradar.law.tree.LawTreeView
import io.github.hdlee73.financenewsradar.law.tree.PdfExporter
import io.github.hdlee73.financenewsradar.law.tree.PdfOptions
import io.github.hdlee73.financenewsradar.law.tree.PdfOptionsDialog
import io.github.hdlee73.financenewsradar.law.tree.PrecedentRow
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    isWide: Boolean,
    splitMode: io.github.hdlee73.financenewsradar.law.data.SplitMode,
    onSplitMode: (io.github.hdlee73.financenewsradar.law.data.SplitMode) -> Unit,
    onOpenPrecedent: (PrecedentSummary) -> Unit,
    onOpenLaw: (LawSummary) -> Unit,
    onOpenAdminRule: (AdminRuleSummary) -> Unit,
    ) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val asOf by vm.asOf.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selectMode by vm.selectMode.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val inline by vm.inlineDelegation.collectAsStateWithLifecycle()

    val searchBar: @Composable ColumnScope.() -> Unit = {
        LawSearchBar(
            query = query,
            asOf = asOf,
            onQueryChange = vm::onQueryChange,
            onSearch = { vm.submit() },
            onAsOfChange = vm::setAsOf,
            showHeadline = state is SearchUiState.Idle,
        )
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is SearchUiState.TreeSuccess -> {
                // 숨김은 보기 화면·PDF 모두에서 빼고(체크하는 중에는 흐리게 보여 줌), 찾기는 맞는 줄과 그 상위만 남긴다.
                // 인라인 방식이면 시행령 한 조의 항들을 위임 위치별로 쪼갠 트리를 쓴다
                val fullRoot = remember(s.tree.root, inline) { if (inline) s.tree.root.withParagraphPieces() else s.tree.root }
                val viewRoot = remember(fullRoot, hidden, selectMode, filter) {
                    TreeFilter.filter(if (selectMode) fullRoot else fullRoot.withoutHidden(hidden), filter)
                }
                val hiddenCount = remember(fullRoot, hidden) { fullRoot.hiddenCount(hidden) }
                val matchCount = remember(viewRoot, filter) { TreeFilter.matchCount(viewRoot, filter) }
                val shown = if (filter.isBlank() && viewRoot === s.tree.root) s else {
                    // 찾는 중에는 맞는 줄이 접혀 있지 않게 모두 펼친다
                    val expanded = if (filter.isBlank()) s.expanded else {
                        viewRoot.flatten().map { it.id }.toSet() +
                            ItemAttachment.attach(viewRoot.article, viewRoot.children).keys.map { "grp:" + it.key }
                    }
                    s.copy(tree = s.tree.copy(root = viewRoot), expanded = expanded)
                }
                val options = remember(filter, selectMode, hidden, inline) {
                    TreeViewOptions(filter.trim(), selectMode, hidden, inline, onHide = vm::setHidden)
                }
                CompositionLocalProvider(LocalTreeViewOptions provides options) {
                    LawTreeView(
                        state = shown,
                        isWide = isWide,
                        header = {
                            searchBar()
                            TreeHeader(
                                tree = s.tree,
                                isFavorite = s.tree.query.raw in favorites,
                                isWide = isWide,
                                onToggleFavorite = { vm.toggleFavorite(s.tree.query.raw) },
                                onVersions = vm::openVersions,
                            )
                        },
                        actions = {
                            TreeActions(
                                // PDF·공유에는 숨긴 항목을 뺀 트리를 쓴다
                                tree = s.tree.copy(root = fullRoot.withoutHidden(hidden)),
                                isWide = isWide,
                                splitMode = splitMode,
                                onSplitMode = onSplitMode,
                                onExpandAll = vm::expandAll,
                                onVersions = vm::openVersions,
                                buildExtras = vm::pdfExtras,
                                filter = filter,
                                onFilter = vm::setFilter,
                                matchCount = matchCount,
                                selectMode = selectMode,
                                onSelectMode = vm::setSelectMode,
                                hiddenCount = hiddenCount,
                                onRestoreHidden = vm::restoreAllHidden,
                                inline = inline,
                                onInline = vm::setInlineDelegation,
                            )
                        },
                        onToggle = vm::toggle,
                        onSelect = vm::select,
                        onOpenPrecedent = onOpenPrecedent,
                    )
                }
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().let { if (isWide) it.padding(horizontal = 24.dp) else it },
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item { Column { searchBar() } }
                when (s) {
                    SearchUiState.Idle -> item {
                        IdleContent(
                            recent = recent,
                            favorites = favorites,
                            onPick = { vm.submit(it) },
                            onClearRecent = vm::clearRecent,
                        )
                    }
                    is SearchUiState.Loading -> item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    is SearchUiState.Error -> item {
                        Card(
                            Modifier.fillMaxWidth().padding(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SelectionContainer { Text(s.message, color = MaterialTheme.colorScheme.onErrorContainer) }
                                if (s.canRetry) Button(onClick = vm::retry) { Text("다시 시도") }
                            }
                        }
                    }
                    is SearchUiState.KeywordSuccess -> keywordResults(
                        result = s.result,
                        onLaw = onOpenLaw,
                        onAdminRule = onOpenAdminRule,
                        onPrecedent = onOpenPrecedent,
                        onArticle = { hit -> vm.submit("${hit.law.name} ${hit.article.number?.display.orEmpty()}") },
                    )
                    is SearchUiState.TreeSuccess -> Unit
                }
            }
        }
    }

    if (versions != VersionSheetState.Hidden) {
        VersionSheet(
            state = versions,
            asOf = asOf,
            onPick = { vm.closeVersions(); vm.setAsOf(it) },
            onDismiss = vm::closeVersions,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TreeHeader(
    tree: DelegationTree,
    isFavorite: Boolean,
    isWide: Boolean,
    onToggleFavorite: () -> Unit,
    onVersions: () -> Unit,
) {
    Column(
        Modifier.padding(horizontal = if (isWide) 0.dp else 14.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().background(io.github.hdlee73.financenewsradar.law.Navy, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tree.law.name, style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f))
                Text(
                    "${tree.root.article.fullTitle} 및 하위 법규",
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (isFavorite) "즐겨찾기 해제" else "즐겨찾기 추가",
                    tint = io.github.hdlee73.financenewsradar.law.Gold,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SuggestionChip(onClick = {}, label = { Text("하위 규정 ${tree.root.descendantCount}개") })
            tree.version?.let { v -> SuggestionChip(onClick = onVersions, label = { Text("${v.effectiveDateLabel} 시행 기준") }) }
        }
        if (tree.isSample) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("데모 데이터입니다. 법령 서버가 아직 연결되지 않은 빌드예요.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (tree.warnings.isNotEmpty()) {
            // 안내문은 한 줄로 접어 두고, 누르면 펼친다
            var open by remember { mutableStateOf(false) }
            Text(
                tree.warnings.joinToString("\n") { "※ $it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
            )
        }
    }
}

/** 스크롤해도 화면 위에 붙어 있는 작업 줄: PDF 저장, 공유, 시행 연혁, 펼치기/접기, 분할 화면 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TreeActions(
    tree: DelegationTree,
    isWide: Boolean,
    splitMode: io.github.hdlee73.financenewsradar.law.data.SplitMode,
    onSplitMode: (io.github.hdlee73.financenewsradar.law.data.SplitMode) -> Unit,
    onExpandAll: (Boolean) -> Unit,
    onVersions: () -> Unit,
    buildExtras: suspend (PdfOptions) -> com.hdlee73.everylaw.core.model.PdfExtras,
    filter: String,
    onFilter: (String) -> Unit,
    matchCount: Int,
    selectMode: Boolean,
    onSelectMode: (Boolean) -> Unit,
    hiddenCount: Int,
    onRestoreHidden: () -> Unit,
    inline: Boolean,
    onInline: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 범위 선택 창: null 이면 닫힘, true 면 공유, false 면 파일로 저장
    var chooseFor by remember { mutableStateOf<Boolean?>(null) }
    var options by remember { mutableStateOf(PdfOptions()) }
    var showCompare by remember { mutableStateOf(false) }
    if (showCompare) {
        io.github.hdlee73.financenewsradar.law.tree.CompareDialog(
            root = tree.root,
            title = "${tree.law.name} ${tree.query.target}",
            onDismiss = { showCompare = false },
            onPdf = { levels -> showCompare = false; options = options.copy(compare = true, levels = levels); chooseFor = false },
        )
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            Toast.makeText(context, "PDF를 만드는 중이에요", Toast.LENGTH_SHORT).show()
            runCatching {
                val extras = buildExtras(options)
                context.contentResolver.openOutputStream(uri)?.use { PdfExporter.write(tree, options, extras, it) } ?: error("파일을 열 수 없어요")
            }.onSuccess { Toast.makeText(context, "PDF를 저장했어요", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "PDF 저장 실패: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    chooseFor?.let { share ->
        PdfOptionsDialog(
            initial = options,
            confirmLabel = if (share) "공유" else "저장",
            onDismiss = { chooseFor = null },
            onConfirm = { chosen ->
                options = chosen
                chooseFor = null
                if (share) scope.launch {
                    Toast.makeText(context, "PDF를 만드는 중이에요", Toast.LENGTH_SHORT).show()
                    runCatching { PdfExporter.share(context, tree, chosen, buildExtras(chosen)) }
                        .onFailure { Toast.makeText(context, "PDF 공유 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                } else saveLauncher.launch(PdfExporter.fileName(tree))
            },
        )
    }

    // 한 줄 아이콘 툴바: 자주 쓰는 PDF·공유·찾기만 두고 나머지는 더보기 메뉴로 보내 조문 영역을 넓힌다.
    var showFilter by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { chooseFor = false }) { Icon(Icons.Outlined.PictureAsPdf, "PDF 저장") }
        IconButton(onClick = { chooseFor = true }) { Icon(Icons.Outlined.Share, "공유") }
        IconButton(onClick = { showCompare = true }) { Icon(Icons.Outlined.TableChart, "비교표 보기") }
        IconButton(onClick = { showFilter = !showFilter }) {
            Icon(Icons.Outlined.Search, "결과 안에서 찾기", tint = if (showFilter || filter.isNotBlank()) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        Spacer(Modifier.weight(1f))
        if (selectMode) TextButton(onClick = { onSelectMode(false) }) { Text("숨기기 끝") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "더보기") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("시행 연혁") },
                    leadingIcon = { Icon(Icons.Outlined.History, null) },
                    onClick = { menuOpen = false; onVersions() },
                )
                if (!isWide) {
                    DropdownMenuItem(
                        text = { Text("모두 펼치기") },
                        leadingIcon = { Icon(Icons.Outlined.UnfoldMore, null) },
                        onClick = { menuOpen = false; onExpandAll(true) },
                    )
                    DropdownMenuItem(
                        text = { Text("모두 접기") },
                        leadingIcon = { Icon(Icons.Outlined.UnfoldLess, null) },
                        onClick = { menuOpen = false; onExpandAll(false) },
                    )
                }
                // 분할 화면 방식을 설정에 가지 않고 바로 바꾼다 (자동 → 항상 분할 → 분할 안 함 → 자동 …)
                val modes = io.github.hdlee73.financenewsradar.law.data.SplitMode.entries
                DropdownMenuItem(
                    text = { Text("화면: ${splitMode.label}") },
                    leadingIcon = { Icon(Icons.Outlined.ViewColumn, null) },
                    onClick = { onSplitMode(modes[(splitMode.ordinal + 1) % modes.size]) },
                )
                DropdownMenuItem(
                    text = { Text(if (inline) "하위법규: 바로 아래" else "하위법규: 묶음") },
                    leadingIcon = { Icon(Icons.Outlined.VerticalAlignBottom, null) },
                    onClick = { onInline(!inline) },
                )
                DropdownMenuItem(
                    text = { Text(if (selectMode) "항목 숨기기 끝" else "항목 숨기기") },
                    leadingIcon = { Icon(if (selectMode) Icons.Outlined.Check else Icons.Outlined.VisibilityOff, null) },
                    onClick = { menuOpen = false; onSelectMode(!selectMode) },
                )
            }
        }
    }
    if (showFilter || filter.isNotBlank()) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilter,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("결과 안에서 찾기 (키워드·제12조)") },
            trailingIcon = {
                IconButton(onClick = { onFilter(""); showFilter = false }) { Icon(Icons.Filled.Close, "찾기 닫기") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        )
    }
    val status = buildList {
        if (filter.isNotBlank()) add(if (matchCount > 0) "맞는 하위 규정 ${matchCount}개" else "맞는 하위 규정이 없어요")
        if (selectMode) add("숨길 항목을 체크하세요")
        else if (hiddenCount > 0) add("숨긴 항목 ${hiddenCount}개 · PDF에서도 빠져요")
    }
    if (status.isNotEmpty() || hiddenCount > 0) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                status.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hiddenCount > 0) {
                if (!selectMode) TextButton(onClick = { onSelectMode(true) }) { Text("숨긴 항목 보기") }
                TextButton(onClick = onRestoreHidden) { Text("모두 되돌리기") }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleContent(
    recent: List<String>,
    favorites: List<String>,
    onPick: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("법조문 하나로\n위임 규정을 한눈에", style = MaterialTheme.typography.headlineMedium)
        Text(
            "법률 → 시행령 → 시행규칙 → 감독규정 → 시행세칙을 거슬러 찾아 계층으로 보여드려요. 조문 번호 없이 입력하면 법령·행정규칙·판례 키워드 검색이 돼요. 법령명을 앞에 붙여 '자본시장법 손실보전'처럼 입력하면 그 법 안에서 조문 제목·내용을 찾아요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("이렇게 찾아보세요", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("자본시장법 제12조", "은행법 제34조", "보험업법 제95조의2", "내부통제").forEach {
                SuggestionChip(onClick = { onPick(it) }, label = { Text(it) })
            }
        }
        if (favorites.isNotEmpty()) {
            Text("보관함", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                favorites.take(8).forEach { AssistChip(onClick = { onPick(it) }, label = { Text(it) }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) }) }
            }
        }
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("최근 조회", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearRecent) { Text("지우기") }
            }
            recent.take(6).forEach { q ->
                ListItem(
                    headlineContent = { Text(q) },
                    leadingContent = { Icon(Icons.Outlined.History, null) },
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(q) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.keywordResults(
    result: KeywordSearchResult,
    onLaw: (LawSummary) -> Unit,
    onAdminRule: (AdminRuleSummary) -> Unit,
    onPrecedent: (PrecedentSummary) -> Unit,
    onArticle: (com.hdlee73.everylaw.core.model.ArticleHit) -> Unit,
) {
    result.scopeLaw?.let { law ->
        item {
            Text(
                "'${law.name}' 안에서 조문 제목·내용을 찾았어요.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
    item { SectionTitle("조문 (제목·내용)", result.articleHits.size, Icons.AutoMirrored.Outlined.MenuBook) }
    if (result.articleHits.isEmpty()) item {
        Text(
            if (result.scopeLaw != null) "조문 제목·내용에 '${result.keyword}'이(가) 들어 있는 조문이 없어요."
            else "'${result.keyword}'이(가) 들어 있는 조문을 찾지 못했어요. " + result.scanNote.ifBlank { "법령명을 앞에 붙이면(예: 자본시장법 ${result.keyword}) 그 법 안에서 찾아요." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
    }
    if (result.articleHits.isNotEmpty() && result.scopeLaw == null && result.scanNote.isNotBlank()) item {
        Text(
            result.scanNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
    }
    items(result.articleHits, key = { "art-${it.law.mst}-${it.article.number}" }) { hit ->
        ResultCard(
            title = listOfNotNull(
                hit.article.number?.display,
                hit.article.title.trim().takeIf { it.isNotEmpty() }?.let { if (it.startsWith("(")) it else "($it)" },
            ).joinToString("") + if (result.scopeLaw == null) " · ${hit.law.name}" else "",
            subtitle = (if (hit.inTitle) "제목에 포함 · " else "내용에 포함 · ") + hit.snippet,
            onClick = { onArticle(hit) },
        )
    }
    if (result.warnings.isNotEmpty()) item {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.warnings.forEach {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
    }
    if (result.scopeLaw == null) item { SectionTitle("법령", result.laws.size, Icons.AutoMirrored.Outlined.MenuBook) }
    if (result.scopeLaw == null) items(result.laws, key = { "law-${it.mst}-${it.name}" }) { law ->
        ResultCard(
            title = law.name,
            subtitle = listOfNotNull(law.kind, law.abbreviation, law.effectiveDate?.let { "시행 ${formatYmd(it)}" }).joinToString(" · "),
            onClick = { onLaw(law) },
        )
    }
    if (result.adminRules.isNotEmpty()) item { SectionTitle("행정규칙 (감독규정·세칙 등)", result.adminRules.size, Icons.Outlined.Description) }
    if (result.adminRules.isNotEmpty()) items(result.adminRules, key = { "adm-${it.serialNo}-${it.name}" }) { r ->
        ResultCard(
            title = r.name,
            subtitle = listOfNotNull(r.kind, r.agency).joinToString(" · "),
            onClick = { onAdminRule(r) },
        )
    }
    item { SectionTitle("판례", result.precedents.size, Icons.Outlined.History) }
    items(result.precedents, key = { "kp-${it.serialNo}-${it.caseNumber}" }) { p ->
        Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { PrecedentRow(p) { onPrecedent(p) } }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("${count}건", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionSheet(
    state: VersionSheetState,
    asOf: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).widthIn(max = 640.dp)) {
            Text("시행 연혁", style = MaterialTheme.typography.titleLarge)
            Text("시점을 고르면 그날 시행 중이던 조문 기준으로 다시 조회해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onPick(null) }, enabled = asOf != null) { Text("현행 기준으로 보기") }
            when (state) {
                VersionSheetState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is VersionSheetState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
                is VersionSheetState.Shown -> LazyColumn {
                    items(state.versions, key = { it.mst + it.effectiveDate }) { v ->
                        val selected = asOf == v.effectiveDate
                        ListItem(
                            headlineContent = { Text("${v.effectiveDateLabel} 시행", fontWeight = if (selected) FontWeight.Black else null) },
                            supportingContent = {
                                Text(listOfNotNull(v.revisionType, v.promulgationDate?.let { "공포 ${formatYmd(it)}" }).joinToString(" · "))
                            },
                            trailingContent = { v.status?.let { Text(it, color = MaterialTheme.colorScheme.primary) } },
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(v.effectiveDate) },
                        )
                    }
                }
                VersionSheetState.Hidden -> Unit
            }
        }
    }
}
