package io.github.hdlee73.financenewsradar.law.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hdlee73.everylaw.core.domain.GetAdminRuleUseCase
import com.hdlee73.everylaw.core.domain.GetLawArticlesUseCase
import com.hdlee73.everylaw.core.domain.GetPrecedentUseCase
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.PrecedentDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 단순 조회 화면용 공통 상태 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val data: T) : LoadState<T>
    data class Error(val message: String) : LoadState<Nothing>
}

class PrecedentViewModel(private val getPrecedent: GetPrecedentUseCase) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<PrecedentDetail>>(LoadState.Loading)
    val state: StateFlow<LoadState<PrecedentDetail>> = _state.asStateFlow()
    private var loadedId: String? = null

    fun load(serialNo: String, force: Boolean = false) {
        if (!force && loadedId == serialNo && _state.value !is LoadState.Error) return
        loadedId = serialNo
        _state.value = LoadState.Loading
        viewModelScope.launch {
            _state.value = try {
                LoadState.Success(getPrecedent(serialNo))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadState.Error(e.message ?: "판례를 불러오지 못했어요.")
            }
        }
    }
}

/** 조문 목록 화면에 보여줄 문서 (법령 또는 행정규칙) */
data class DocumentView(
    val title: String,
    val subtitle: String?,
    val articles: List<Article>,
    /** 법령이면 조문을 눌러 위임 트리를 조회할 수 있다. */
    val law: LawSummary?,
)

/** 키워드 검색에서 고른 법령·행정규칙의 조문 목록 */
class DocumentViewModel(
    private val getLawArticles: GetLawArticlesUseCase,
    private val getAdminRule: GetAdminRuleUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<DocumentView>>(LoadState.Loading)
    val state: StateFlow<LoadState<DocumentView>> = _state.asStateFlow()
    private var loadedKey: String? = null

    fun loadLaw(law: LawSummary, force: Boolean = false) = load("law:${law.mst}", force) {
        DocumentView(law.name, law.kind, getLawArticles(law), law)
    }

    fun loadAdminRule(serialNo: String, force: Boolean = false) = load("adm:$serialNo", force) {
        val doc = getAdminRule(serialNo)
        DocumentView(doc.name, doc.agency, doc.articles, null)
    }

    private fun load(key: String, force: Boolean, block: suspend () -> DocumentView) {
        if (!force && loadedKey == key && _state.value !is LoadState.Error) return
        loadedKey = key
        _state.value = LoadState.Loading
        viewModelScope.launch {
            _state.value = try {
                LoadState.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadState.Error(e.message ?: "조문을 불러오지 못했어요.")
            }
        }
    }
}
