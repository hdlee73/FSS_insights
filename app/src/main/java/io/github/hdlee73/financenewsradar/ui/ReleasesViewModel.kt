package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.AgencyRepository
import io.github.hdlee73.financenewsradar.data.SettingsStore
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import io.github.hdlee73.financenewsradar.model.UsefulLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgencyUiState(
    val latest: List<ReleaseItem> = emptyList(),
    val latestLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** 비어 있지 않으면 검색 결과를 보여 주는 중. */
    val searchQuery: String = "",
    val results: List<ReleaseItem> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val canLoadMore: Boolean = false,
    val nextPage: Int = 1
) {
    val inSearch: Boolean get() = searchQuery.isNotBlank()
}

data class ReleasesUiState(
    val agencies: Map<AgencyId, AgencyUiState> = AgencyId.entries.associateWith { AgencyUiState() },
    val links: List<UsefulLink> = emptyList()
) {
    fun of(agency: AgencyId): AgencyUiState = agencies[agency] ?: AgencyUiState()
}

class ReleasesViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val repository = AgencyRepository()
    private val jobs = mutableMapOf<AgencyId, Job>()

    private val _state = MutableStateFlow(ReleasesUiState(links = settingsStore.loadLinks()))
    val state: StateFlow<ReleasesUiState> = _state.asStateFlow()

    private fun update(agency: AgencyId, change: (AgencyUiState) -> AgencyUiState) {
        _state.update { it.copy(agencies = it.agencies + (agency to change(it.of(agency)))) }
    }

    /** 탭을 처음 열 때 한 번만 가져온다. 새로고침은 [refresh]. */
    fun ensureLatest(agency: AgencyId) {
        val current = _state.value.of(agency)
        if (!current.latestLoaded && !current.isLoading) refresh(agency)
    }

    fun refresh(agency: AgencyId) {
        jobs[agency]?.cancel()
        jobs[agency] = viewModelScope.launch {
            update(agency) { it.copy(isLoading = true, error = null) }
            runCatching { repository.latest(agency) }
                .onSuccess { items -> update(agency) { it.copy(latest = items, latestLoaded = true, isLoading = false) } }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    update(agency) { it.copy(isLoading = false, latestLoaded = true, error = friendly(e)) }
                }
        }
    }

    fun search(agency: AgencyId, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        jobs[agency]?.cancel()
        jobs[agency] = viewModelScope.launch {
            update(agency) {
                it.copy(searchQuery = clean, results = emptyList(), isSearching = true, searchError = null, canLoadMore = false, nextPage = 1)
            }
            runCatching { repository.search(agency, clean, 1) }
                .onSuccess { page ->
                    update(agency) { it.copy(results = page.items, isSearching = false, canLoadMore = page.hasMore, nextPage = page.nextPage) }
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    update(agency) { it.copy(isSearching = false, searchError = friendly(e)) }
                }
        }
    }

    fun loadMore(agency: AgencyId) {
        val current = _state.value.of(agency)
        if (!current.inSearch || current.isSearching || !current.canLoadMore) return
        jobs[agency]?.cancel()
        jobs[agency] = viewModelScope.launch {
            update(agency) { it.copy(isSearching = true, searchError = null) }
            runCatching { repository.search(agency, current.searchQuery, current.nextPage) }
                .onSuccess { page ->
                    update(agency) { state ->
                        val merged = (state.results + page.items).distinctBy { it.link }
                        state.copy(results = merged, isSearching = false, canLoadMore = page.hasMore, nextPage = page.nextPage)
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    update(agency) { it.copy(isSearching = false, searchError = friendly(e)) }
                }
        }
    }

    fun clearSearch(agency: AgencyId) {
        jobs[agency]?.cancel()
        update(agency) { it.copy(searchQuery = "", results = emptyList(), isSearching = false, searchError = null, canLoadMore = false, nextPage = 1) }
    }

    fun saveLinks(links: List<UsefulLink>) {
        val clean = links.map { UsefulLink(it.name.trim(), normalizeUrl(it.url)) }
            .filter { it.name.isNotBlank() && it.url.isNotBlank() }
        settingsStore.saveLinks(clean)
        _state.update { it.copy(links = clean) }
    }

    fun resetLinks() {
        settingsStore.saveLinks(UsefulLink.DEFAULTS)
        _state.update { it.copy(links = UsefulLink.DEFAULTS) }
    }

    private fun friendly(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "목록을 불러오지 못했습니다. 네트워크 연결을 확인해 주세요."

    companion object {
        /** 주소에 http/https가 없으면 https를 붙인다. */
        fun normalizeUrl(raw: String): String {
            val text = raw.trim()
            if (text.isBlank()) return ""
            return if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
        }
    }
}
