package io.github.hdlee73.financenewsradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceNewsRadarApp(viewModel: NewsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var queryText by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("F", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("금융뉴스 레이더", fontWeight = FontWeight.ExtraBold)
                            Text(
                                "감독·시장 이슈 모니터링",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
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
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SearchControls(
                query = queryText,
                onQueryChange = { queryText = it },
                onSearch = { viewModel.search(queryText) },
                settings = state.settings,
                onKeyword = {
                    queryText = it
                    viewModel.search(it)
                },
                naverReady = state.credentials.isComplete,
                onProvider = viewModel::setProvider,
                onScope = viewModel::setScope,
                onTimeRange = viewModel::setTimeRange,
                onOpenSettings = { settingsOpen = true }
            )

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
                    viewModel.refreshHome()
                },
                onRefresh = {
                    if (state.isHome) viewModel.refreshHome() else viewModel.search(state.query)
                }
            )

            when {
                state.isLoading -> LoadingView()
                state.visibleArticles.isEmpty() -> EmptyView(bookmarksOnly = state.bookmarksOnly)
                else -> ArticleTimeline(
                    articles = state.visibleArticles,
                    isLoadingMore = state.isLoadingMore,
                    canLoadMore = state.canLoadMore && !state.bookmarksOnly,
                    onLoadMore = viewModel::loadMore,
                    onBookmark = viewModel::toggleBookmark
                )
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(
                    brush = Brush.linearGradient(listOf(Color(0xFF006C67), Color(0xFF124E78))),
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("오늘의 금융 이슈", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text("정확한 검색식으로 놓치는 기사를 줄여보세요", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(shape = CircleShape, color = Color(0xFF7EE2C7).copy(alpha = 0.2f)) {
                        Text("● LIVE", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFF9FF4DC), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("회사명·이슈·검색식") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color(0xFF7EE2C7),
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                    Button(
                        onClick = onSearch,
                        enabled = query.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("검색", fontWeight = FontWeight.Bold) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item { AssistChip(onClick = { onQueryChange(appendQueryToken(query, "AND")) }, label = { Text("AND") }) }
                    item { AssistChip(onClick = { onQueryChange(appendQueryToken(query, "OR")) }, label = { Text("OR") }) }
                    item { AssistChip(onClick = { onQueryChange(appendQueryToken(query, "(")) }, label = { Text("(") }) }
                    item { AssistChip(onClick = { onQueryChange(appendQueryToken(query, ")")) }, label = { Text(")") }) }
                    item { AssistChip(onClick = { onQueryChange(appendQueryToken(query, "\"문구\"")) }, label = { Text("\"문구\"") }) }
                }
                Text(
                    "예: 금감원 AND (증권사 OR 자산운용사) · 공백은 AND로 처리",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Text("빠른 검색", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(settings.keywords) { keyword ->
                AssistChip(onClick = { onKeyword(keyword) }, label = { Text(keyword) })
            }
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
                    label = { Text(provider.label) }
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(OutletScope.entries) { scope ->
                FilterChip(
                    selected = settings.outletScope == scope,
                    onClick = { onScope(scope) },
                    label = { Text(scope.label) }
                )
            }
            item { Spacer(Modifier.width(4.dp)) }
            items(TimeRange.entries) { range ->
                FilterChip(
                    selected = settings.timeRange == range,
                    onClick = { onTimeRange(range) },
                    label = { Text(range.label) }
                )
            }
        }

        if (settings.provider == NewsProviderType.COMBINED && !naverReady) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("네이버 키를 연결하면 통합 검색 범위가 넓어집니다.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSettings) { Text("연결") }
                }
            }
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
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                val resultSummary = if (bookmarksOnly) {
                    "${provider.label} · 즐겨찾기 ${count}건"
                } else {
                    buildString {
                        append("${provider.label} · ${fetchedCount}건 수집 → ${count}건 표시")
                        if (duplicateCount > 0) append(" · 중복 ${duplicateCount}건")
                        if (outletExcludedCount > 0) append(" · 범위 제외 ${outletExcludedCount}건")
                        if (failedQueryCount > 0) append(" · 실패 ${failedQueryCount}건")
                    }
                }
                Text(
                    resultSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onHome) { Icon(Icons.Default.Home, contentDescription = "맞춤 뉴스") }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("최신 기사를 모으고 있습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyView(bookmarksOnly: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            if (bookmarksOnly) "즐겨찾기한 기사가 없습니다.\n기사 카드의 북마크 버튼을 눌러 저장해 보세요."
            else "조건에 맞는 기사를 찾지 못했습니다.\n검색 기간을 넓히거나 전체 언론으로 바꿔 보세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ArticleTimeline(
    articles: List<NewsArticle>,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onBookmark: (NewsArticle) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(articles, key = { _, article -> article.stableId }) { index, article ->
            val section = daySection(article.publishedAt)
            val previousSection = articles.getOrNull(index - 1)?.let { daySection(it.publishedAt) }
            if (section != previousSection) {
                Text(
                    section,
                    modifier = Modifier.padding(top = if (index == 0) 4.dp else 12.dp, start = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            ArticleCard(article = article, onBookmark = { onBookmark(article) })
        }

        if (canLoadMore || isLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (isLoadingMore) CircularProgressIndicator(Modifier.size(26.dp))
                    else OutlinedButton(onClick = onLoadMore) { Text("이전 기사 더 불러오기") }
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(article: NewsArticle, onBookmark: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.link)))
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            if (article.isPriority) MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text(
                        article.source,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (article.isPriority) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                        Text(
                            "감독 핵심",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    relativeTime(article.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                article.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (article.matchedKeywords.isNotEmpty()) {
                Text(
                    article.matchedKeywords.joinToString(" · ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("원문 열기", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onBookmark, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (article.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "즐겨찾기",
                        tint = if (article.isBookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        val shareText = "${article.title}\n${article.source} · ${absoluteTime(article.publishedAt)}\n${article.link}"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, article.title)
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "기사 공유"))
                    },
                    modifier = Modifier.size(40.dp)
                ) { Icon(Icons.Default.Share, contentDescription = "공유") }
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
                                NaverCredentials(clientId, clientSecret)
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

private fun appendQueryToken(query: String, token: String): String = when (token) {
    ")" -> query.trimEnd() + ")"
    "(" -> if (query.isBlank()) "(" else query.trimEnd() + " ("
    else -> if (query.isBlank()) "$token " else query.trimEnd() + " $token "
}
