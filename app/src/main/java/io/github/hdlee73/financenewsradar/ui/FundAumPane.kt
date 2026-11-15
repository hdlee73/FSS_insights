package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.FundAumCompany
import io.github.hdlee73.financenewsradar.data.FundAumDataset
import io.github.hdlee73.financenewsradar.data.FundAumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FundAumUiState(val data: FundAumDataset? = null, val loading: Boolean = false, val error: String? = null)

/** 금융회사 주요 현황의 '펀드 순자산' 탭이 쓰는 자료(운용사·증권사별 공모/사모 순자산총액). */
class FundAumViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FundAumRepository(application)
    private val _state = MutableStateFlow(FundAumUiState())
    val state: StateFlow<FundAumUiState> = _state.asStateFlow()

    fun ensure() {
        val s = _state.value
        if (s.data != null || s.loading) return
        load(force = false)
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (_state.value.data == null) repository.cached()?.let { d -> _state.update { it.copy(data = d) } }
            try {
                val d = repository.load(force)
                _state.update { it.copy(data = d, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: "자료를 받지 못했습니다.") }
            }
        }
    }
}

/** 억원 숫자를 "1조 2,345억"처럼. 0은 "-". */
internal fun eokText(v: Long): String = when {
    v == 0L -> "-"
    v >= 10_000 -> "%,d조 %,d억".format(v / 10_000, v % 10_000).replace(" 0억", "")
    else -> "%,d억".format(v)
}

internal fun dateLabel(ymd: String): String =
    if (ymd.length == 8) "${ymd.substring(0, 4)}.${ymd.substring(4, 6)}.${ymd.substring(6)}" else ymd

@Composable
fun FundAumPane(company: String, sector: String = "G", modifier: Modifier = Modifier) {
    val vm: FundAumViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensure() }
    val data = state.data
    val found = remember(data, company, sector) { data?.find(company, sector) }
    when {
        data == null && state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) }
        data == null -> CenterMessage(state.error ?: "자료가 없습니다.", isError = true, actionLabel = "다시 시도", onAction = { vm.load(force = true) })
        found == null -> CenterMessage("금융투자협회 회사별 펀드 설정규모에서 '$company' 이름으로 된 자료를 찾지 못했습니다.\n(펀드를 설정·운용하지 않는 회사이거나 회사명 표기가 다를 수 있습니다.)")
        else -> FundAumBody(found, data, modifier)
    }
}

/** 찾은 회사의 순자산 표와 안내 문구(화면 캡처 시험에서도 이 부분만 그린다). */
@Composable
internal fun FundAumBody(found: FundAumCompany, data: FundAumDataset, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FundAumTable(found, data)
        Text(
            "자료출처: 금융투자협회(FreeSIS) 종합통계 포털 '회사별설정규모'(펀드, 순자산총액, 투자지역 전체). " +
                "기관전용 사모펀드는 제외한 값이며 단위는 억원입니다. " +
                "최직근 분기말·연말은 그 일자의 값입니다. 금융투자협회 자료를 매일 자동으로 모아 보여 주므로 원문과 다를 수 있습니다. 갱신: ${data.generatedAt.take(16).replace('T', ' ')}",
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun FundAumTable(c: FundAumCompany, data: FundAumDataset) {
    val heads = listOf("최근일자" to data.asOf.recent, "최직근 분기말" to data.asOf.quarter, "최직근 연말" to data.asOf.year)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("펀드 순자산총액", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("", Modifier.weight(0.8f))
            heads.forEach { (label, ymd) ->
                Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    Text(dateLabel(ymd), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
        }
        RowDivider()
        AumRow("공모", List(3) { c.pub[it] }, false)
        AumRow("사모", List(3) { c.pri[it] }, false)
        AumRow("합계", List(3) { c.total(it) }, true)
    }
}

@Composable
private fun AumRow(label: String, values: List<Long>, bold: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(label, Modifier.weight(0.8f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            values.forEach { v ->
                Text(eokText(v), Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.End)
            }
        }
        RowDivider()
    }
}
