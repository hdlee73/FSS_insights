package io.github.hdlee73.financenewsradar.accounting

import io.github.hdlee73.financenewsradar.CrashLogger
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.accounting.data.AccountingRepository
import io.github.hdlee73.financenewsradar.accounting.data.AuditAction
import io.github.hdlee73.financenewsradar.accounting.data.BoardItem
import io.github.hdlee73.financenewsradar.accounting.data.DataSource
import io.github.hdlee73.financenewsradar.accounting.data.DisclosureCase
import io.github.hdlee73.financenewsradar.accounting.data.StandardItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataState(
    val loading: Boolean = true,
    val audit: List<AuditAction> = emptyList(),
    val board: List<BoardItem> = emptyList(),
    val standards: List<StandardItem> = emptyList(),
    val disclosure: List<DisclosureCase> = emptyList(),
    val disclosureUpdatedAt: String = "",
    val auditUpdatedAt: String = "",
    val source: DataSource = DataSource.SEED,
    val error: String? = null
)

class AccountingViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AccountingRepository(app)
    private val _data = MutableStateFlow(DataState())
    val data: StateFlow<DataState> = _data.asStateFlow()
    private var loadJob: Job? = null

    init { reload() }

    /** 중복 호출 시 진행 중인 작업을 취소하고 새로 시작한다. */
    fun reload() {
        loadJob?.cancel()
        _data.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val audit = repo.loadAudit()
                val board = repo.loadBoard()
                val standards = repo.loadStandards()
                val disclosure = repo.loadDisclosure()
                _data.value = DataState(
                    loading = false, audit = audit.data.items, board = board.data.items, standards = standards,
                    disclosure = disclosure.data.items, disclosureUpdatedAt = disclosure.data.generatedAt,
                    auditUpdatedAt = audit.data.generatedAt, source = audit.source,
                    error = if (audit.source == DataSource.SEED && board.source == DataSource.SEED && audit.data.items.isEmpty()) "데이터를 불러오지 못했습니다. 네트워크를 확인하고 다시 시도하세요." else null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "데이터 로드 실패", e)
                _data.update { it.copy(loading = false, error = "데이터를 불러오지 못했습니다. (${e.javaClass.simpleName})") }
            }
        }
    }

}
