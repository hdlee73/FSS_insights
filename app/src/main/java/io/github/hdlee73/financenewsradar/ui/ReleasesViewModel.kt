package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.AgencyRepository
import io.github.hdlee73.financenewsradar.data.ArticleText
import io.github.hdlee73.financenewsradar.data.PdfText
import io.github.hdlee73.financenewsradar.data.SettingsStore
import io.github.hdlee73.financenewsradar.data.SummaryEngine
import io.github.hdlee73.financenewsradar.data.SummaryStore
import io.github.hdlee73.financenewsradar.model.AgencyGroup
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.CustomInstitute
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import io.github.hdlee73.financenewsradar.model.UsefulLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

data class AgencyUiState(
    val latest: List<ReleaseItem> = emptyList(),
    val latestLoaded: Boolean = false,
    val isLoading: Boolean = false,
    /** 첫 쪽은 이미 보여 주고 나머지 쪽을 백그라운드에서 읽는 중. */
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    /** 비어 있지 않으면 검색 결과를 보여 주는 중. */
    val searchQuery: String = "",
    val results: List<ReleaseItem> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val canLoadMore: Boolean = false,
    val nextPage: Int = 1,
    /** 지난번 확인 이후 새로 올라온 자료의 링크. */
    val newLinks: Set<String> = emptySet()
) {
    val inSearch: Boolean get() = searchQuery.isNotBlank()
}

data class ReleasesUiState(
    val agencies: Map<AgencyId, AgencyUiState> = AgencyId.entries.associateWith { AgencyUiState() },
    val links: List<UsefulLink> = emptyList(),
    val saved: List<ReleaseItem> = emptyList(),
    /** 사용자가 추가한 연구소와, 주소별 화면 상태. */
    val institutes: List<CustomInstitute> = emptyList(),
    val custom: Map<String, AgencyUiState> = emptyMap(),
    /** 자료 링크 → 기기 내 Gemini Nano가 만든 한국어 요약(지원 기기에서만 채워진다). */
    val summaries: Map<String, String> = emptyMap(),
    /** 요약이 아직 없는 자료의 상태 문구(대기·진행·불가 사유). Gemini Nano가 있는 기기에서만 채워진다. */
    val summaryNotes: Map<String, String> = emptyMap()
) {
    fun of(agency: AgencyId): AgencyUiState = agencies[agency] ?: AgencyUiState()
    fun ofCustom(url: String): AgencyUiState = custom[url] ?: AgencyUiState()
    fun institutesOf(group: AgencyGroup): List<CustomInstitute> = institutes.filter { it.group == group }
    val savedLinks: Set<String> get() = saved.map { it.link }.toSet()
}

class ReleasesViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val repository = AgencyRepository(application)
    private val jobs = mutableMapOf<AgencyId, Job>()
    private val customJobs = mutableMapOf<String, Job>()
    private val summaryStore = SummaryStore(application)
    private val summaryJobs = mutableMapOf<String, Job>()
    private val summarySkipped = mutableSetOf<String>()

    private val _state = MutableStateFlow(
        ReleasesUiState(
            links = settingsStore.loadLinks(), saved = settingsStore.savedReleases(), institutes = loadInstitutesMigrated(),
            summaries = summaryStore.load()
        )
    )
    val state: StateFlow<ReleasesUiState> = _state.asStateFlow()

    /** 이제 기본 탭인 BCBS·FCA·MAS를 이전에 직접 추가해 둔 항목은 중복되지 않도록 목록에서 뺀다. */
    private fun loadInstitutesMigrated(): List<CustomInstitute> {
        val saved = settingsStore.loadInstitutes()
        val builtIn = listOf("bis.org", "fca.org.uk", "mas.gov.sg")
        val kept = saved.filterNot { inst ->
            val host = runCatching { java.net.URI(inst.url).host }.getOrNull().orEmpty().lowercase()
            builtIn.any { host == it || host.endsWith(".$it") }
        }
        if (kept.size != saved.size) settingsStore.saveInstitutes(kept)
        return kept
    }

    private fun update(agency: AgencyId, change: (AgencyUiState) -> AgencyUiState) {
        _state.update { it.copy(agencies = it.agencies + (agency to change(it.of(agency)))) }
    }

    /** 탭을 처음 열 때 한 번만 가져온다. 새로고침은 [refresh]. */
    fun ensureLatest(agency: AgencyId) {
        val current = _state.value.of(agency)
        if (!current.latestLoaded && !current.isLoading) refresh(agency)
    }

    /** 같은 묶음의 다른 기관 탭도 미리 백그라운드에서 읽어 둔다(아직 안 읽은 기관만). */
    fun preload(agencies: List<AgencyId>) {
        agencies.forEach(::ensureLatest)
    }

    fun refresh(agency: AgencyId) {
        // 새로고침하면 지난번 요약 실패 자료도 다시 시도한다.
        summarySkipped.clear()
        _state.update { it.copy(summaryNotes = emptyMap()) }
        jobs[agency]?.cancel()
        jobs[agency] = viewModelScope.launch {
            update(agency) { it.copy(isLoading = true, isLoadingMore = false, error = null) }
            runCatching {
                repository.latest(agency, agency.latestCount) { partial ->
                    // 첫 쪽이 오는 대로 바로 보여 주고, 나머지 쪽은 읽히는 대로 이어 붙인다.
                    update(agency) { it.copy(latest = partial, isLoading = false, isLoadingMore = true) }
                }
            }
                .onSuccess { items ->
                    val links = items.map { it.link }
                    val seen = settingsStore.seenLinks(agency)
                    val fresh = if (seen == null) emptySet() else links.filter { it !in seen }.toSet()
                    settingsStore.saveSeenLinks(agency, seen.orEmpty() + links)
                    update(agency) {
                        it.copy(
                            latest = items, latestLoaded = true, isLoading = false, isLoadingMore = false,
                            newLinks = (it.newLinks + fresh).intersect(links.toSet())
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    update(agency) { it.copy(isLoading = false, isLoadingMore = false, latestLoaded = true, error = friendly(e)) }
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

    /** 저장함에 담기/빼기. 이미 있으면 뺀다. */
    fun toggleSaved(item: ReleaseItem) {
        val current = _state.value.saved
        val next = if (current.any { it.link == item.link }) current.filter { it.link != item.link } else listOf(item) + current
        settingsStore.saveReleases(next)
        _state.update { it.copy(saved = next) }
    }

    // ---- 사용자가 추가한 연구소 ----
    private fun updateCustom(url: String, change: (AgencyUiState) -> AgencyUiState) {
        _state.update { it.copy(custom = it.custom + (url to change(it.ofCustom(url)))) }
    }

    fun addInstitute(name: String, rawUrl: String, group: AgencyGroup = AgencyGroup.RESEARCH) {
        val url = normalizeUrl(rawUrl)
        val clean = name.trim()
        if (clean.isBlank() || url.isBlank()) return
        val next = _state.value.institutes.filter { it.url != url } + CustomInstitute(clean, url, group)
        settingsStore.saveInstitutes(next)
        _state.update { it.copy(institutes = next) }
    }

    fun removeInstitute(institute: CustomInstitute) {
        customJobs.remove(institute.url)?.cancel()
        val next = _state.value.institutes.filter { it.url != institute.url }
        settingsStore.saveInstitutes(next)
        _state.update { it.copy(institutes = next, custom = it.custom - institute.url) }
    }

    fun ensureCustom(institute: CustomInstitute) {
        val current = _state.value.ofCustom(institute.url)
        if (!current.latestLoaded && !current.isLoading) refreshCustom(institute)
    }

    fun refreshCustom(institute: CustomInstitute) {
        customJobs[institute.url]?.cancel()
        customJobs[institute.url] = viewModelScope.launch {
            updateCustom(institute.url) { it.copy(isLoading = true, error = null) }
            runCatching { repository.latestCustom(institute, 20) }
                .onSuccess { items ->
                    val key = "CUSTOM_${institute.url}"
                    val links = items.map { it.link }
                    val seen = settingsStore.seenLinks(key)
                    val fresh = if (seen == null) emptySet() else links.filter { it !in seen }.toSet()
                    settingsStore.saveSeenLinks(key, seen.orEmpty() + links)
                    updateCustom(institute.url) {
                        it.copy(latest = items, latestLoaded = true, isLoading = false, newLinks = (it.newLinks + fresh).intersect(links.toSet()))
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    updateCustom(institute.url) { it.copy(isLoading = false, latestLoaded = true, error = friendly(e)) }
                }
        }
    }

    fun searchCustom(institute: CustomInstitute, query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        customJobs[institute.url]?.cancel()
        customJobs[institute.url] = viewModelScope.launch {
            updateCustom(institute.url) { it.copy(searchQuery = clean, results = emptyList(), isSearching = true, searchError = null, canLoadMore = false) }
            runCatching { repository.searchCustom(institute, clean) }
                .onSuccess { items -> updateCustom(institute.url) { it.copy(results = items, isSearching = false) } }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    updateCustom(institute.url) { it.copy(isSearching = false, searchError = friendly(e)) }
                }
        }
    }

    fun clearCustomSearch(institute: CustomInstitute) {
        customJobs[institute.url]?.cancel()
        updateCustom(institute.url) { it.copy(searchQuery = "", results = emptyList(), isSearching = false, searchError = null) }
    }

    fun saveLinks(links: List<UsefulLink>) {
        val clean = links.map { UsefulLink(it.name.trim(), normalizeUrl(it.url), it.description.trim().replace(Regex("[\\r\\n]+"), " ")) }
            .filter { it.name.isNotBlank() && it.url.isNotBlank() }
        settingsStore.saveLinks(clean)
        _state.update { it.copy(links = clean) }
    }

    fun resetLinks() {
        settingsStore.saveLinks(UsefulLink.DEFAULTS)
        _state.update { it.copy(links = UsefulLink.DEFAULTS) }
    }

    /** 지금 보고 있는 기관 탭의 요약을 만든다(다른 탭 작업은 멈춰 화면에 보이는 목록부터 채운다). */
    fun summarizeVisible(agency: AgencyId) = summarize(agency.name, _state.value.of(agency).latest)

    fun summarizeVisibleCustom(institute: CustomInstitute) = summarize("CUSTOM_${institute.url}", _state.value.ofCustom(institute.url).latest)

    private fun setNotes(change: (Map<String, String>) -> Map<String, String>) =
        _state.update { it.copy(summaryNotes = change(it.summaryNotes)) }

    /** 요약용으로 읽을 주소: 자본시장연구원 '바로보기'는 뷰어 페이지라 같은 자료의 PDF 내려받기 주소로 바꾼다. */
    private fun summarySource(link: String): String =
        if (link.contains("kcmi.re.kr/flexer/view")) link.replace("/flexer/view", "/common/downloadw") else link

    /**
     * Gemini Nano가 있는 기기에서만: 목록의 각 자료 본문을 읽어 한국어 요약을 만들어 제목 아래에 보여 준다.
     * 이미 만든 요약은 다시 만들지 않고, 기기가 지원하지 않으면 아무 것도 하지 않아 화면은 그대로다.
     * 처리 상태(대기·진행·불가 사유)는 [ReleasesUiState.summaryNotes]로 목록에 작게 보여 준다.
     * 본문 읽기는 서버가 요청 폭주(429)로 막지 않도록 한 건씩 간격을 두고, 읽는 동안 앞 건의 Gemini Nano 호출이 이어진다.
     */
    private fun summarize(key: String, items: List<ReleaseItem>) {
        if (items.isEmpty()) return
        summaryJobs.filterKeys { it != key }.values.forEach { it.cancel() }
        summaryJobs[key]?.cancel()
        summaryJobs[key] = viewModelScope.launch {
            if (!SummaryEngine.isAvailable()) return@launch
            val todo = items.filter { !_state.value.summaries.containsKey(it.link) && it.link !in summarySkipped }
            if (todo.isEmpty()) return@launch
            try {
                setNotes { notes -> notes + todo.associate { it.link to "요약 대기 중" } }
                val gate = kotlinx.coroutines.sync.Semaphore(1)
                kotlinx.coroutines.coroutineScope {
                    for (item in todo) launch {
                        // 읽기(내려받기)는 한 건씩 간격을 두고, 요약 생성은 읽기와 겹쳐 진행한다.
                        val read = gate.withPermit {
                            setNotes { it + (item.link to "요약 중…") }
                            val source = summarySource(item.link)
                            val fetched = if (ArticleText.isPdf(source)) PdfText.extract(getApplication(), source)
                            else ArticleText.fetch(getApplication(), source, item.title)
                            kotlinx.coroutines.delay(700)
                            fetched
                        }
                        run {
                            val outcome = if (read.text != null) SummaryEngine.summarize(item.title, read.text) else read
                            if (outcome.text != null) {
                                val next = _state.value.summaries + (item.link to outcome.text)
                                _state.update { it.copy(summaries = next, summaryNotes = it.summaryNotes - item.link) }
                                summaryStore.save(next)
                            } else {
                                summarySkipped += item.link
                                setNotes { it + (item.link to "요약 불가 · ${outcome.reason}") }
                            }
                        }
                    }
                }
            } finally {
                // 중간에 멈췄다면(다른 탭으로 이동 등) 대기·진행 문구는 지운다. 실패 사유는 남긴다.
                setNotes { notes -> notes.filterValues { !it.startsWith("요약 대기") && !it.startsWith("요약 중") } }
            }
        }
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
