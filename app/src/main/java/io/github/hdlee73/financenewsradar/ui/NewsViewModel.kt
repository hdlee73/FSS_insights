package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.NewsRepository
import io.github.hdlee73.financenewsradar.data.NewsText
import io.github.hdlee73.financenewsradar.data.SettingsStore
import io.github.hdlee73.financenewsradar.model.AppSettings
import io.github.hdlee73.financenewsradar.model.NaverCredentials
import io.github.hdlee73.financenewsradar.model.NewsArticle
import io.github.hdlee73.financenewsradar.model.NewsProviderType
import io.github.hdlee73.financenewsradar.model.OutletScope
import io.github.hdlee73.financenewsradar.model.TimeRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class NewsUiState(
    val settings: AppSettings = AppSettings(),
    val credentials: NaverCredentials = NaverCredentials(),
    val articles: List<NewsArticle> = emptyList(),
    val query: String = "",
    val currentTitle: String = "맞춤 뉴스",
    val isHome: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val nextStart: Int = 1,
    val error: String? = null,
    val lastUpdated: Instant? = null,
    val bookmarksOnly: Boolean = false,
    val fetchedCount: Int = 0,
    val duplicateCount: Int = 0,
    val outletExcludedCount: Int = 0,
    val failedQueryCount: Int = 0,
    val sourceNote: String? = null,
    val trends: List<io.github.hdlee73.financenewsradar.data.KeywordTrend> = emptyList(),
    val trendsLoading: Boolean = false
) {
    val visibleArticles: List<NewsArticle>
        get() = if (bookmarksOnly) articles.filter { it.isBookmarked } else articles
}

class NewsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val repository = NewsRepository()
    private var searchJob: Job? = null

    private val _state = MutableStateFlow(
        NewsUiState(
            settings = settingsStore.loadSettings(),
            credentials = settingsStore.loadCredentials()
        )
    )
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    init { refreshHome() }

    fun refreshHome() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val snapshot = _state.value
            _state.update {
                it.copy(isLoading = true, error = null, isHome = true, currentTitle = "맞춤 뉴스", query = "")
            }
            runCatching { repository.home(snapshot.settings, snapshot.credentials) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            articles = withBookmarks(page.articles),
                            isLoading = false,
                            canLoadMore = false,
                            nextStart = 1,
                            lastUpdated = Instant.now(),
                            fetchedCount = page.fetchedCount,
                            duplicateCount = page.duplicateCount,
                            outletExcludedCount = page.outletExcludedCount,
                            failedQueryCount = page.failedQueryCount,
                            sourceNote = page.sourceNote
                        )
                    }
                }
                .onFailure(::handleFailure)
        }
    }

    fun loadTrends() {
        if (_state.value.trendsLoading) return
        viewModelScope.launch {
            val snapshot = _state.value
            _state.update { it.copy(trendsLoading = true) }
            val result = runCatching { repository.trends(snapshot.settings, snapshot.credentials) }.getOrDefault(emptyList())
            _state.update { it.copy(trends = result, trendsLoading = false) }
        }
    }

    fun search(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val snapshot = _state.value
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    query = cleanQuery,
                    currentTitle = "‘$cleanQuery’ 검색 결과",
                    isHome = false,
                    bookmarksOnly = false
                )
            }
            runCatching { repository.search(cleanQuery, snapshot.settings, snapshot.credentials) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            articles = withBookmarks(page.articles),
                            isLoading = false,
                            canLoadMore = page.hasMore,
                            nextStart = page.nextStart,
                            lastUpdated = Instant.now(),
                            fetchedCount = page.fetchedCount,
                            duplicateCount = page.duplicateCount,
                            outletExcludedCount = page.outletExcludedCount,
                            failedQueryCount = page.failedQueryCount,
                            sourceNote = page.sourceNote
                        )
                    }
                }
                .onFailure(::handleFailure)
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.isHome || snapshot.query.isBlank() || snapshot.isLoadingMore || !snapshot.canLoadMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, error = null) }
            runCatching {
                repository.search(snapshot.query, snapshot.settings, snapshot.credentials, snapshot.nextStart)
            }.onSuccess { page ->
                _state.update { current ->
                    val merged = (current.articles + withBookmarks(page.articles))
                        .distinctBy { NewsText.normalizeTitle(it.title).ifBlank { it.link } }
                        .sortedByDescending { it.publishedAt }
                    val crossPageDuplicates = current.articles.size + page.articles.size - merged.size
                    current.copy(
                        articles = merged,
                        isLoadingMore = false,
                        canLoadMore = page.hasMore,
                        nextStart = page.nextStart,
                        fetchedCount = current.fetchedCount + page.fetchedCount,
                        duplicateCount = current.duplicateCount + page.duplicateCount + crossPageDuplicates,
                        outletExcludedCount = current.outletExcludedCount + page.outletExcludedCount,
                        failedQueryCount = current.failedQueryCount + page.failedQueryCount
                    )
                }
            }.onFailure(::handleFailure)
        }
    }

    fun setScope(scope: OutletScope) = updateSettings(_state.value.settings.copy(outletScope = scope))
    fun setTimeRange(timeRange: TimeRange) = updateSettings(_state.value.settings.copy(timeRange = timeRange))
    fun setProvider(provider: NewsProviderType) = updateSettings(_state.value.settings.copy(provider = provider))

    fun saveSettings(settings: AppSettings, credentials: NaverCredentials) {
        val sanitized = settings.copy(
            keywords = settings.keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10)
                .ifEmpty { AppSettings.DEFAULT_KEYWORDS }
        )
        settingsStore.saveSettings(sanitized)
        settingsStore.saveCredentials(credentials)
        _state.update { it.copy(settings = sanitized, credentials = credentials, bookmarksOnly = false) }
        refreshHome()
    }

    fun toggleBookmark(article: NewsArticle) {
        val enabled = settingsStore.toggleBookmark(article.link)
        _state.update { current ->
            current.copy(articles = current.articles.map {
                if (it.link == article.link) it.copy(isBookmarked = enabled) else it
            })
        }
    }

    fun toggleBookmarksOnly() {
        _state.update { it.copy(bookmarksOnly = !it.bookmarksOnly) }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    private fun updateSettings(settings: AppSettings) {
        settingsStore.saveSettings(settings)
        _state.update { it.copy(settings = settings) }
        if (_state.value.isHome) refreshHome() else search(_state.value.query)
    }

    private fun withBookmarks(articles: List<NewsArticle>): List<NewsArticle> {
        val bookmarks = settingsStore.bookmarks()
        return articles.map { it.copy(isBookmarked = it.link in bookmarks) }
    }

    private fun handleFailure(throwable: Throwable) {
        if (throwable is CancellationException) return
        val friendly = when {
            throwable.message.orEmpty().contains("Client ID") -> throwable.message
            throwable.message.orEmpty().contains("401") || throwable.message.orEmpty().contains("403") ->
                "네이버 API 인증을 확인해 주세요. 설정에서 새 키를 저장할 수 있습니다."
            else -> throwable.message?.takeIf { it.isNotBlank() }
                ?: "기사를 불러오지 못했습니다. 네트워크 연결을 확인해 주세요."
        }
        _state.update { it.copy(isLoading = false, isLoadingMore = false, error = friendly) }
    }
}
