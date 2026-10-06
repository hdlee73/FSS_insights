package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.ArticleReaderActivity
import io.github.hdlee73.financenewsradar.model.AppSettings
import io.github.hdlee73.financenewsradar.model.NewsArticle
import io.github.hdlee73.financenewsradar.model.NewsProviderType
import io.github.hdlee73.financenewsradar.model.OutletScope
import io.github.hdlee73.financenewsradar.model.TimeRange
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** 뉴스검색 탭: 맞춤 키워드 피드, 직접 검색, 즐겨찾기, 키워드 모니터링. */
@Composable
fun NewsScreen(viewModel: NewsViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    var monitorOpen by rememberSaveable { mutableStateOf(false) }
    if (monitorOpen) {
        androidx.activity.compose.BackHandler { monitorOpen = false }
        MonitorScreen(
            viewModel = viewModel,
            onBack = { monitorOpen = false },
            onPick = { keyword -> monitorOpen = false; viewModel.search(keyword) },
            modifier = modifier
        )
    } else {
        NewsMain(viewModel, onOpenSettings, onOpenMonitor = { viewModel.loadTrends(); monitorOpen = true }, modifier = modifier)
    }
}

@Composable
private fun NewsMain(viewModel: NewsViewModel, onOpenSettings: () -> Unit, onOpenMonitor: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    var queryText by rememberSaveable { mutableStateOf("") }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val prepareSearch = {
        dismissKeyboard()
        filtersExpanded = false
        scope.launch { listState.scrollToItem(0) }
        Unit
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "title") {
                LargeTitle("뉴스검색") {
                    IconButton(onClick = onOpenMonitor) { Icon(Icons.Default.Insights, contentDescription = "키워드 모니터링") }
                    IconButton(onClick = viewModel::toggleBookmarksOnly) {
                        Icon(
                            if (state.bookmarksOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "즐겨찾기만 보기",
                            tint = if (state.bookmarksOnly) androidx.compose.ui.graphics.Color(0xFFFFB38F) else androidx.compose.ui.graphics.Color.White
                        )
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "설정") }
                }
            }
            item(key = "search-controls") {
                SearchControls(
                    query = queryText,
                    filtersExpanded = filtersExpanded,
                    onToggleFilters = { filtersExpanded = !filtersExpanded },
                    onQueryChange = { queryText = it },
                    onSearch = {
                        if (queryText.isNotBlank()) {
                            prepareSearch()
                            viewModel.search(queryText)
                        }
                    },
                    settings = state.settings,
                    onKeyword = {
                        queryText = it
                        prepareSearch()
                        viewModel.search(it)
                    },
                    onScope = viewModel::setScope,
                    onTimeRange = viewModel::setTimeRange,
                    onOpenSettings = onOpenSettings
                )
            }
            item(key = "result-header") {
                ResultHeader(
                    title = if (state.bookmarksOnly) "즐겨찾기" else state.currentTitle,
                    count = state.visibleArticles.size,
                    provider = state.settings.provider,
                    bookmarksOnly = state.bookmarksOnly,
                    fetchedCount = state.fetchedCount,
                    duplicateCount = state.duplicateCount,
                    outletExcludedCount = state.outletExcludedCount,
                    failedQueryCount = state.failedQueryCount,
                    sourceNote = state.sourceNote,
                    onHome = {
                        queryText = ""
                        prepareSearch()
                        viewModel.refreshHome()
                    },
                    onRefresh = {
                        if (state.isHome) viewModel.refreshHome() else viewModel.search(state.query)
                    }
                )
            }
            when {
                state.isLoading -> item(key = "loading") { LoadingView() }
                state.visibleArticles.isEmpty() -> item(key = "empty") {
                    CenterMessage(
                        if (state.bookmarksOnly) "즐겨찾기한 기사가 없습니다.\n기사의 북마크 버튼을 눌러 저장해 보세요."
                        else "조건에 맞는 기사를 찾지 못했습니다.\n검색 기간을 넓히거나 전체 언론으로 바꿔 보세요."
                    )
                }
                else -> {
                    itemsIndexed(state.visibleArticles, key = { _, article -> article.stableId }) { index, article ->
                        val section = daySection(article.publishedAt)
                        val previousSection = state.visibleArticles.getOrNull(index - 1)?.let { daySection(it.publishedAt) }
                        Column {
                            if (section != previousSection) SectionLabel(section, modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp))
                            ArticleRow(article = article, onBookmark = { viewModel.toggleBookmark(article) })
                            RowDivider()
                        }
                    }
                    if (state.isLoadingMore || (state.canLoadMore && !state.bookmarksOnly)) {
                        item(key = "load-more") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                else OutlinedButton(onClick = viewModel::loadMore) { Text("이전 기사 더 불러오기") }
                            }
                        }
                    }
                }
            }
        }

        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120) {
            SmallFloatingActionButton(
                onClick = {
                    dismissKeyboard()
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.VerticalAlignTop, contentDescription = "맨 위로 이동")
            }
        }
    }
}

@Composable
private fun SearchControls(
    query: String,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    settings: AppSettings,
    onKeyword: (String) -> Unit,
    onScope: (OutletScope) -> Unit,
    onTimeRange: (TimeRange) -> Unit,
    onOpenSettings: () -> Unit
) {
    var help by rememberSaveable { mutableStateOf<String?>(null) }
    help?.let { topic ->
        SearchHelpDialog(topic, onDismiss = { help = null }, onOpenSettings = { help = null; onOpenSettings() })
    }
    // 검색 조건 영역: 색이 다른 패널로 감싸 아래 '결과'와 확실히 구분한다.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            )
            .padding(top = 10.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SearchPill(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "키워드·회사명 검색",
                onSearch = onSearch,
                containerColor = MaterialTheme.colorScheme.surface,
                outlined = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSearch, enabled = query.isNotBlank()) { Text("검색", fontWeight = FontWeight.SemiBold) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(settings.keywords) { keyword -> PillChip(label = keyword, onPanel = true, onClick = { onKeyword(keyword) }) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${settings.outletScope.label} · ${settings.timeRange.label}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onToggleFilters) {
                Text("필터", style = MaterialTheme.typography.labelLarge)
                Icon(
                    if (filtersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (filtersExpanded) "필터 접기" else "필터 펼치기",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (filtersExpanded) {
            FilterTitle("언론 범위·기간", helpDescription = "30대 언론 목록과 선정 기준") { help = "outlets" }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(OutletScope.entries) { scope ->
                    PillChip(label = scope.label, onPanel = true, selected = settings.outletScope == scope, onClick = { onScope(scope) })
                }
                items(TimeRange.entries) { range ->
                    PillChip(label = range.label, onPanel = true, selected = settings.timeRange == range, onClick = { onTimeRange(range) })
                }
            }
            Text(
                "공백·AND: 모두 포함 / OR: 하나 이상 / \"문구\": 정확히 일치",
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FilterTitle(text: String, helpDescription: String, onHelp: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onHelp, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.HelpOutline, contentDescription = helpDescription, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultHeader(
    title: String,
    count: Int,
    provider: NewsProviderType,
    bookmarksOnly: Boolean,
    fetchedCount: Int,
    duplicateCount: Int,
    outletExcludedCount: Int,
    failedQueryCount: Int,
    sourceNote: String?,
    onHome: () -> Unit,
    onRefresh: () -> Unit
) {
    var detailsExpanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { detailsExpanded = !detailsExpanded }.padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${count}건 · ${if (bookmarksOnly) "즐겨찾기" else (sourceNote?.substringBefore(" (") ?: provider.label)} · 상세 ${if (detailsExpanded) "▴" else "▾"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onHome) { Icon(Icons.Default.Home, contentDescription = "맞춤 뉴스", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (detailsExpanded) {
            val resultSummary = if (bookmarksOnly) {
                "즐겨찾기 ${count}건"
            } else {
                buildString {
                    append("${sourceNote ?: provider.label} · 가져온 ${fetchedCount}건 중 ${count}건 표시")
                    if (duplicateCount > 0) append(" · 중복 ${duplicateCount}건 제외")
                    if (outletExcludedCount > 0) append(" · 언론 범위 ${outletExcludedCount}건 제외")
                    if (failedQueryCount > 0) append(" · 일부 검색 ${failedQueryCount}건 실패")
                }
            }
            Text(resultSummary, modifier = Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            Text("최신 기사를 모으고 있습니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArticleRow(article: NewsArticle, onBookmark: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { ArticleReaderActivity.open(context, article.link, article.title) }
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagLabel(article.source.take(12), accent = article.isPriority)
            Text(
                "  ·  ${relativeTime(article.publishedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onBookmark, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (article.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "즐겨찾기",
                    modifier = Modifier.size(22.dp),
                    tint = if (article.isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { ArticleReaderActivity.open(context, article.link, article.title) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Share, contentDescription = "원문 보기·공유", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            article.title,
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            article.summary,
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (article.matchedKeywords.isNotEmpty()) {
            Text(
                article.matchedKeywords.joinToString(" · ") { "#$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")
private val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)

private fun daySection(instant: Instant): String {
    val date = instant.atZone(seoulZone).toLocalDate()
    val today = LocalDate.now(seoulZone)
    return when (date) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> date.format(DateTimeFormatter.ofPattern("M월 d일 E요일", Locale.KOREAN))
    }
}

private fun relativeTime(instant: Instant): String {
    if (instant == Instant.EPOCH) return "시간 미상"
    val duration = Duration.between(instant, Instant.now())
    return when {
        duration.isNegative -> "방금"
        duration.toMinutes() < 1 -> "방금"
        duration.toHours() < 1 -> "${duration.toMinutes()}분 전"
        duration.toDays() < 1 -> "${duration.toHours()}시간 전"
        else -> absoluteTime(instant)
    }
}

private fun absoluteTime(instant: Instant): String = instant.atZone(seoulZone).format(fullDateFormatter)
