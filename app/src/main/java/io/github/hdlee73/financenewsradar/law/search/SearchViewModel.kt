package io.github.hdlee73.financenewsradar.law.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hdlee73.everylaw.core.domain.DelegationProgress
import com.hdlee73.everylaw.core.domain.GetDelegationTreeUseCase
import com.hdlee73.everylaw.core.domain.GetLawVersionsUseCase
import com.hdlee73.everylaw.core.domain.LawDomainException
import com.hdlee73.everylaw.core.domain.SearchKeywordUseCase
import com.hdlee73.everylaw.core.domain.withParagraphPieces
import com.hdlee73.everylaw.core.domain.withoutHidden
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.KeywordSearchResult
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawVersion
import com.hdlee73.everylaw.core.parser.DrfFormatException
import com.hdlee73.everylaw.core.parser.LawQueryParser
import io.github.hdlee73.financenewsradar.law.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** 검색 화면의 UI 상태 (Loading / Success / Error) */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data class Loading(val message: String) : SearchUiState
    data class TreeSuccess(
        val tree: DelegationTree,
        /** 아코디언에서 펼친 노드 */
        val expanded: Set<String>,
        /** 분할 화면 오른쪽에 띄운 노드. [PRECEDENTS_PANE]이면 판례 목록 */
        val selectedId: String,
    ) : SearchUiState
    data class KeywordSuccess(val result: KeywordSearchResult) : SearchUiState
    data class Error(val message: String, val canRetry: Boolean) : SearchUiState

    companion object {
        const val PRECEDENTS_PANE = "__precedents__"
    }
}

sealed interface VersionSheetState {
    data object Hidden : VersionSheetState
    data object Loading : VersionSheetState
    data class Shown(val versions: List<LawVersion>) : VersionSheetState
    data class Failed(val message: String) : VersionSheetState
}

class SearchViewModel(
    private val getDelegationTree: GetDelegationTreeUseCase,
    private val searchKeyword: SearchKeywordUseCase,
    private val getLawVersions: GetLawVersionsUseCase,
    private val buildPdfExtras: com.hdlee73.everylaw.core.domain.BuildPdfExtrasUseCase,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 조회 기준 시행일 (yyyyMMdd). null = 현행 */
    private val _asOf = MutableStateFlow<String?>(null)
    val asOf: StateFlow<String?> = _asOf.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** 결과 안에서 찾는 글 (키워드 또는 "제12조") */
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    /** 하위 법규를 위임 위치 바로 아래에 보여 주는지 */
    val inlineDelegation: StateFlow<Boolean> = settings.settings.map { it.inlineDelegation }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setInlineDelegation(on: Boolean) {
        viewModelScope.launch { settings.setInlineDelegation(on) }
    }

    /** 숨길 항목을 체크하는 중인지 (켜져 있는 동안은 숨긴 항목도 흐리게 보인다) */
    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    /** 지금 보는 조문에서 사용자가 숨긴 하위 규정의 키 */
    val hidden: StateFlow<Set<String>> = combine(_state, settings.settings) { s, app ->
        (s as? SearchUiState.TreeSuccess)?.let { app.hidden[articleKey(it.tree)] }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _versions = MutableStateFlow<VersionSheetState>(VersionSheetState.Hidden)
    val versions: StateFlow<VersionSheetState> = _versions.asStateFlow()

    val recent: StateFlow<List<String>> = settings.settings.map { it.recent }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites: StateFlow<List<String>> = settings.settings.map { it.favorites }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 화면 안 "이전 결과" 스택. 시스템 뒤로가기가 앱을 끄지 않고 직전 결과로 돌아가게 한다. */
    private val backStack = ArrayDeque<SearchUiState>()
    /** 처음 화면(Idle)이 아니면 뒤로가기를 화면 안에서 처리한다. */
    val canGoBack: StateFlow<Boolean> = _state.map { it !is SearchUiState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var job: Job? = null
    private var lastSubmitted: String = ""

    fun onQueryChange(text: String) {
        _query.value = text
    }

    fun setAsOf(ymd: String?) {
        _asOf.value = ymd
        // 이미 조문 결과를 보고 있으면 새 시점으로 다시 조회
        val current = _state.value
        if (current is SearchUiState.TreeSuccess) runTree(current.tree.query.raw, pushHistory = false)
        else if (current is SearchUiState.KeywordSuccess) runKeyword(current.result.keyword)
    }

    /**
     * 검색 버튼. "법령명 + 조문"이면 위임 트리, 조문 번호가 없으면 키워드 통합 검색.
     */
    fun submit(text: String = _query.value) {
        val q = text.trim()
        if (q.isEmpty()) return
        _query.value = q
        if (LawQueryParser.parse(q) is LawQueryParser.Result.Ok) runTree(q) else runKeyword(q)
    }

    fun retry() = submit(lastSubmitted)

    private fun runTree(q: String, pushHistory: Boolean = true) {
        lastSubmitted = q
        val before = _state.value
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = SearchUiState.Loading("조문을 해석하는 중")
            getDelegationTree(q, _asOf.value)
                .catch { e -> if (e is CancellationException) throw e else _state.value = errorOf(e) }
                .collect { p ->
                    when (p) {
                        is DelegationProgress.Step -> _state.value = SearchUiState.Loading(p.message)
                        is DelegationProgress.Done -> {
                            if (pushHistory) push(before)
                            settings.addRecent(q)
                            resetViewTools()
                            _state.value = SearchUiState.TreeSuccess(
                                tree = p.tree,
                                expanded = defaultExpanded(p.tree),
                                selectedId = p.tree.root.id,
                            )
                        }
                    }
                }
        }
    }

    private fun runKeyword(q: String) {
        lastSubmitted = q
        val before = _state.value
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = SearchUiState.Loading("'$q' 키워드로 법령·행정규칙·판례 찾는 중")
            _state.value = try {
                val result = searchKeyword(q, _asOf.value)
                push(before)
                settings.addRecent(q)
                SearchUiState.KeywordSuccess(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorOf(e)
            }
        }
    }

    // ---------- 트리 조작 ----------

    fun toggle(nodeId: String) = updateTree { s ->
        s.copy(expanded = if (nodeId in s.expanded) s.expanded - nodeId else s.expanded + nodeId)
    }

    fun expandAll(expand: Boolean) = updateTree { s ->
        val root = if (inlineDelegation.value) s.tree.root.withParagraphPieces() else s.tree.root
        val groups = com.hdlee73.everylaw.core.domain.ItemAttachment.attach(root.article, root.children).keys.map { "grp:" + it.key }
        s.copy(expanded = if (expand) (root.flatten().map { it.id } + groups).toSet() else setOf(root.id))
    }

    fun setFilter(text: String) {
        _filter.value = text
    }

    fun setSelectMode(on: Boolean) {
        _selectMode.value = on
    }

    fun setHidden(nodeKey: String, hide: Boolean) {
        val tree = (_state.value as? SearchUiState.TreeSuccess)?.tree ?: return
        viewModelScope.launch { settings.setHidden(articleKey(tree), nodeKey, hide) }
    }

    fun restoreAllHidden() {
        val tree = (_state.value as? SearchUiState.TreeSuccess)?.tree ?: return
        _selectMode.value = false
        viewModelScope.launch { settings.clearHidden(articleKey(tree)) }
    }

    private fun resetViewTools() {
        _filter.value = ""
        _selectMode.value = false
    }

    private fun articleKey(tree: DelegationTree) = tree.law.name + "|" + tree.query.article.display

    fun select(nodeId: String) = updateTree { it.copy(selectedId = nodeId) }

    private fun updateTree(block: (SearchUiState.TreeSuccess) -> SearchUiState.TreeSuccess) {
        _state.update { if (it is SearchUiState.TreeSuccess) block(it) else it }
    }

    private fun defaultExpanded(tree: DelegationTree): Set<String> =
        // 항별로 쪼갠 조각(id에 #)도 처음부터 펼쳐 둔다
        (tree.root.flatten() + tree.root.withParagraphPieces().flatten())
            .filter { it.level == LawLevel.LAW || it.level == LawLevel.DECREE }.map { it.id }.toSet()

    // ---------- 시행 연혁 ----------

    fun openVersions() {
        val tree = (_state.value as? SearchUiState.TreeSuccess)?.tree ?: return
        _versions.value = VersionSheetState.Loading
        viewModelScope.launch {
            _versions.value = try {
                VersionSheetState.Shown(getLawVersions(tree.law))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VersionSheetState.Failed(errorOf(e).message)
            }
        }
    }

    /** PDF 범위에 맞춰 판례 본문·인용 조문을 내려받는다. */
    suspend fun pdfExtras(options: io.github.hdlee73.financenewsradar.law.tree.PdfOptions): com.hdlee73.everylaw.core.model.PdfExtras {
        val tree = (_state.value as? SearchUiState.TreeSuccess)?.tree ?: return com.hdlee73.everylaw.core.model.PdfExtras()
        // 숨긴 하위 규정은 PDF에서도 뺀다
        val shown = tree.copy(root = tree.root.withoutHidden(hidden.value))
        return buildPdfExtras(shown, options.precedentBodies, options.relatedArticles)
    }

    fun closeVersions() {
        _versions.value = VersionSheetState.Hidden
    }

    // ---------- 즐겨찾기 ----------

    fun toggleFavorite(q: String) {
        viewModelScope.launch { settings.toggleFavorite(q) }
    }

    fun clearRecent() {
        viewModelScope.launch { settings.clearRecent() }
    }

    // ---------- 뒤로가기 ----------

    private fun push(previous: SearchUiState) {
        if (previous is SearchUiState.Idle || previous is SearchUiState.TreeSuccess || previous is SearchUiState.KeywordSuccess) {
            backStack.addLast(previous)
        }
    }

    /** @return 화면 안에서 처리했으면 true */
    fun back(): Boolean {
        job?.cancel()
        val current = _state.value
        if (current is SearchUiState.Loading || current is SearchUiState.Error) {
            _state.value = backStack.lastOrNull() ?: SearchUiState.Idle
            if (backStack.isNotEmpty()) backStack.removeLast()
        } else {
            val prev = backStack.removeLastOrNull() ?: run {
                if (current is SearchUiState.Idle) return false
                SearchUiState.Idle
            }
            _state.value = prev
        }
        resetViewTools()
        _query.value = when (val s = _state.value) {
            is SearchUiState.TreeSuccess -> s.tree.query.raw
            is SearchUiState.KeywordSuccess -> s.result.keyword
            else -> ""
        }
        return true
    }

    private fun errorOf(e: Throwable): SearchUiState.Error = when (e) {
        is LawDomainException -> SearchUiState.Error(e.message ?: "검색 결과가 없습니다.", canRetry = e !is LawDomainException.InvalidQuery)
        is DrfFormatException -> SearchUiState.Error("국가법령정보 API 응답 오류: ${e.message}", canRetry = true)
        is retrofit2.HttpException -> SearchUiState.Error(
            when (e.code()) {
                401 -> "서버 인증에 실패했어요. 앱과 서버의 토큰이 맞는지 확인이 필요해요. (HTTP 401)"
                404 -> "법령 서버(/law)를 찾지 못했어요. 서버에 법령 중계 기능이 아직 배포되지 않았을 수 있어요. (HTTP 404)"
                503 -> "법령 서버에 국가법령정보 인증값(LAW_OC)이 아직 설정되지 않았어요. (HTTP 503)"
                else -> "국가법령정보 서버 응답 오류: HTTP ${e.code()} ${e.message()}"
            },
            canRetry = true,
        )
        is IOException -> SearchUiState.Error("네트워크 연결을 확인해 주세요. (${e::class.simpleName}: ${e.message})", canRetry = true)
        else -> SearchUiState.Error("${e::class.simpleName}: ${e.message?.takeIf { it.isNotBlank() } ?: "(메시지 없음)"}", canRetry = true)
    }
}
