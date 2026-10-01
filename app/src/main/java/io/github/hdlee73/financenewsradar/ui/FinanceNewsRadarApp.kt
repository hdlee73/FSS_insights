package io.github.hdlee73.financenewsradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hdlee73.financenewsradar.ArticleReaderActivity
import io.github.hdlee73.financenewsradar.model.NaverApiType
import io.github.hdlee73.financenewsradar.model.AppSettings
import io.github.hdlee73.financenewsradar.model.NaverCredentials
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceNewsRadarApp(viewModel: NewsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    var queryText by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val prepareSearch = {
        dismissKeyboard()
        filtersExpanded = false
        scope.launch { listState.scrollToItem(0) }
        Unit
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120) {
                SmallFloatingActionButton(onClick = {
                    dismissKeyboard()
                    scope.launch { listState.animateScrollToItem(0) }
                }, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.VerticalAlignTop, contentDescription = "맨 위로 이동")
                }
            }
        },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "금융뉴스 레이더",
                        modifier = Modifier.weight(1f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = viewModel::toggleBookmarksOnly) {
                        Icon(
                            if (state.bookmarksOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "즐겨찾기만 보기"
                        )
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                }
            }
        }
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
                    naverReady = state.credentials.isComplete,
                    onProvider = viewModel::setProvider,
                    onScope = viewModel::setScope,
                    onTimeRange = viewModel::setTimeRange,
                    onOpenSettings = { settingsOpen = true }
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
                state.visibleArticles.isEmpty() -> item(key = "empty") { EmptyView(bookmarksOnly = state.bookmarksOnly) }
                else -> {
                    itemsIndexed(state.visibleArticles, key = { _, article -> article.stableId }) { index, article ->
                        val section = daySection(article.publishedAt)
                        val previousSection = state.visibleArticles.getOrNull(index - 1)?.let { daySection(it.publishedAt) }
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (section != previousSection) {
                                Text(
                                    section,
                                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp, start = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            ArticleCard(article = article, onBookmark = { viewModel.toggleBookmark(article) })
                        }
                    }
                    if (state.isLoadingMore || (state.canLoadMore && !state.bookmarksOnly)) {
                        item(key = "load-more") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(26.dp))
                                else OutlinedButton(onClick = viewModel::loadMore) { Text("이전 기사 더 불러오기") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            current = state.settings,
            credentials = state.credentials,
            onDismiss = { settingsOpen = false },
            onSave = { settings, credentials ->
                viewModel.saveSettings(settings, credentials)
                settingsOpen = false
            }
        )
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
    naverReady: Boolean,
    onProvider: (NewsProviderType) -> Unit,
    onScope: (OutletScope) -> Unit,
    onTimeRange: (TimeRange) -> Unit,
    onOpenSettings: () -> Unit
) {
    var help by rememberSaveable { mutableStateOf<String?>(null) }
    help?.let { topic ->
        SearchHelpDialog(topic, onDismiss = { help = null }, onOpenSettings = { help = null; onOpenSettings() })
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = "검색어" },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text("키워드·회사명 검색", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                innerTextField()
                            }
                        }
                    }
                )
            }
            Button(
                onClick = onSearch,
                enabled = query.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("검색") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(settings.keywords) { keyword ->
                AssistChip(onClick = { onKeyword(keyword) }, label = { Text(keyword, style = MaterialTheme.typography.labelMedium) })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${settings.provider.label} · ${settings.outletScope.label} · ${settings.timeRange.label}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onToggleFilters) {
                Text("필터", style = MaterialTheme.typography.labelMedium)
                Icon(if (filtersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (filtersExpanded) "필터 접기" else "필터 펼치기", modifier = Modifier.size(18.dp))
            }
        }
        if (filtersExpanded) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("검색 방식", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { help = "search" }) { Icon(Icons.Default.HelpOutline, contentDescription = "검색 방식과 네이버 키 발급 도움말", modifier = Modifier.size(20.dp)) }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(NewsProviderType.entries) { provider ->
                    FilterChip(
                        selected = settings.provider == provider,
                        onClick = {
                            if (provider == NewsProviderType.NAVER && !naverReady) onOpenSettings()
                            else onProvider(provider)
                        },
                        label = { Text(provider.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("언론 범위·기간", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { help = "outlets" }) { Icon(Icons.Default.HelpOutline, contentDescription = "30대 언론 목록과 선정 기준", modifier = Modifier.size(20.dp)) }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(OutletScope.entries) { scope ->
                    FilterChip(
                        selected = settings.outletScope == scope,
                        onClick = { onScope(scope) },
                        label = { Text(scope.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
                item { Spacer(Modifier.width(4.dp)) }
                items(TimeRange.entries) { range ->
                    FilterChip(
                        selected = settings.timeRange == range,
                        onClick = { onTimeRange(range) },
                        label = { Text(range.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            if (settings.provider == NewsProviderType.COMBINED && !naverReady) {
                Text(
                    "네이버 키 연결 · 통합 검색 범위 확대",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "공백·AND: 모두 포함 / OR: 하나 이상 / \"문구\": 정확히 일치",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    onHome: () -> Unit,
    onRefresh: () -> Unit
) {
    var detailsExpanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { detailsExpanded = !detailsExpanded }) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${count}건 표시 · 상세 ${if (detailsExpanded) "▴" else "▾"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onHome) { Icon(Icons.Default.Home, contentDescription = "맞춤 뉴스") }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
        if (detailsExpanded) {
            val resultSummary = if (bookmarksOnly) {
                "${provider.label} · 즐겨찾기 ${count}건"
            } else {
                buildString {
                    append("${provider.label} · 가져온 ${fetchedCount}건 중 ${count}건 표시")
                    if (duplicateCount > 0) append(" · 중복 ${duplicateCount}건 제외")
                    if (outletExcludedCount > 0) append(" · 언론 범위 ${outletExcludedCount}건 제외")
                    if (failedQueryCount > 0) append(" · 일부 검색 ${failedQueryCount}건 실패")
                }
            }
            Text(resultSummary, modifier = Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("최신 기사를 모으고 있습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyView(bookmarksOnly: Boolean) {
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            if (bookmarksOnly) "즐겨찾기한 기사가 없습니다.\n기사 카드의 북마크 버튼을 눌러 저장해 보세요."
            else "조건에 맞는 기사를 찾지 못했습니다.\n검색 기간을 넓히거나 전체 언론으로 바꿔 보세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ArticleCard(article: NewsArticle, onBookmark: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            ArticleReaderActivity.open(context, article.link, article.title)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    article.source,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    relativeTime(article.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onBookmark) {
                    Icon(if (article.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "즐겨찾기", tint = if (article.isBookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ArticleReaderActivity.open(context, article.link, article.title) }) {
                    Icon(Icons.Default.Share, contentDescription = "원문 PDF 저장·공유")
                }
            }

            Text(
                article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                article.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (article.matchedKeywords.isNotEmpty()) {
                Text(
                    article.matchedKeywords.joinToString(" · ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    current: AppSettings,
    credentials: NaverCredentials,
    onDismiss: () -> Unit,
    onSave: (AppSettings, NaverCredentials) -> Unit
) {
    var keywords by remember(current) {
        mutableStateOf((current.keywords + List(5) { "" }).take(5))
    }
    var provider by remember(current) { mutableStateOf(current.provider) }
    var clientId by remember(credentials) { mutableStateOf(credentials.clientId) }
    var clientSecret by remember(credentials) { mutableStateOf(credentials.clientSecret) }
    var apiType by remember(credentials) { mutableStateOf(credentials.apiType) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    if (helpOpen) SearchHelpDialog("search", onDismiss = { helpOpen = false }, onOpenSettings = { helpOpen = false })
    val naverKeyComplete = clientId.isNotBlank() && clientSecret.isNotBlank()
    val canSave = provider != NewsProviderType.NAVER || naverKeyComplete

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기") }
                    Text("검색 설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                current.copy(keywords = keywords, provider = provider),
                                NaverCredentials(clientId, clientSecret, apiType)
                            )
                        }
                    ) { Text("저장", fontWeight = FontWeight.Bold) }
                }

                Column(
                    Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("맞춤 뉴스 키워드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "첫 화면에서 아래 5개 키워드의 최신 기사를 한꺼번에 모읍니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    keywords.forEachIndexed { index, value ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { next -> keywords = keywords.toMutableList().also { it[index] = next } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("키워드 ${index + 1}") },
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("검색 방식", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    NewsProviderType.entries.forEach { option ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { provider = option },
                            colors = CardDefaults.cardColors(
                                containerColor = if (provider == option) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(option.label, fontWeight = FontWeight.Bold)
                                Text(option.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (provider != NewsProviderType.GOOGLE_RSS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("네이버 연결", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { helpOpen = true }) { Icon(Icons.Default.HelpOutline, contentDescription = "네이버 연결 절차") }
                        }
                        NaverApiType.entries.forEach { type ->
                            FilterChip(selected = apiType == type, onClick = { apiType = type }, label = { Text(type.label) })
                        }
                        Text(
                            if (provider == NewsProviderType.COMBINED)
                                "네이버 키는 선택 사항입니다. 입력하면 Google과 네이버 결과를 합칩니다. 키는 Android Keystore로 암호화됩니다."
                            else "키는 이 기기의 Android Keystore로 암호화되며 GitHub 소스나 APK에 포함되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Naver Client ID") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Naver Client Secret") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        if (provider == NewsProviderType.NAVER && !naverKeyComplete) {
                            Text("두 값을 모두 입력해야 네이버 검색을 사용할 수 있습니다.", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "‘30대 언론’은 종합지·방송·통신·경제지 30곳을 앱 내부 기준으로 선별합니다. ‘전체 언론’으로 바꾸면 지역지와 전문지를 함께 검색합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
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
