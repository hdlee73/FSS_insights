package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.FISIS_RATIO_METHODS
import io.github.hdlee73.financenewsradar.data.FISIS_SECTORS
import io.github.hdlee73.financenewsradar.data.FisisApi
import io.github.hdlee73.financenewsradar.data.FisisAsset
import io.github.hdlee73.financenewsradar.data.FisisCompany
import io.github.hdlee73.financenewsradar.data.FisisMetric
import io.github.hdlee73.financenewsradar.data.FisisProfile
import io.github.hdlee73.financenewsradar.data.fisisMonthLabel
import io.github.hdlee73.financenewsradar.data.fisisTermLabel
import io.github.hdlee73.financenewsradar.data.fisisValueText
import io.github.hdlee73.financenewsradar.data.fisisWon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 비교에 담을 수 있는 회사 수. */
private const val MAX_COMPARE = 3

/** 현황·비교에 보여 주는 재무 지표(순서 고정). */
private val SUMMARY_KEYS = listOf("assets", "liab", "equity", "revenue", "netincome")

/** 재무정보 탭에 보여 주는 지표(순서 고정). */
private val FINANCE_KEYS = listOf("assets", "liab", "equity", "capital", "revenue", "expense", "netincome")

/** 화면 안에서의 위치: 회사 목록 → 회사 상세 또는 비교. */
sealed interface FisisView {
    data object Companies : FisisView
    data class Detail(val company: FisisCompany) : FisisView
    data object Compare : FisisView
}

data class FisisUiState(
    val sector: String = FISIS_SECTORS.first().code,
    val companies: Map<String, List<FisisCompany>> = emptyMap(),
    val loadingCompanies: Boolean = false,
    /** 권역별 총자산(순위용): 회사코드 → 값. */
    val assets: Map<String, Map<String, FisisAsset>> = emptyMap(),
    val ranking: Boolean = false,
    val picks: List<FisisCompany> = emptyList(),
    val view: FisisView = FisisView.Companies,
    /** "권역|회사코드" → 경영정보. */
    val profiles: Map<String, FisisProfile> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val error: String? = null
)

private fun profileKey(sector: String, company: String) = "$sector|$company"

class FisisViewModel(application: Application) : AndroidViewModel(application) {
    private val api = FisisApi()
    val isConfigured: Boolean get() = api.isConfigured
    private val _state = MutableStateFlow(FisisUiState())
    val state: StateFlow<FisisUiState> = _state.asStateFlow()

    init { if (api.isConfigured) loadCompanies(_state.value.sector) }

    fun selectSector(code: String) {
        if (code == _state.value.sector) return
        _state.update { it.copy(sector = code, picks = emptyList(), error = null) }
        loadCompanies(code)
    }

    fun loadCompanies(sector: String, force: Boolean = false) {
        if (!force && _state.value.companies.containsKey(sector)) return
        viewModelScope.launch {
            _state.update { it.copy(loadingCompanies = true, error = null) }
            runCatching { api.companies(sector) }
                .onSuccess { list ->
                    _state.update { it.copy(companies = it.companies + (sector to list), loadingCompanies = false) }
                    loadRanking(sector)
                }
                .onFailure { e -> _state.update { it.copy(loadingCompanies = false, error = e.message ?: "금융회사 목록을 불러오지 못했습니다.") } }
        }
    }

    /** 총자산 순위를 서버에서 30곳씩 이어 받는다(받는 대로 목록 순서가 바뀐다). */
    private fun loadRanking(sector: String) {
        if (_state.value.assets.containsKey(sector)) return
        viewModelScope.launch {
            _state.update { it.copy(ranking = true) }
            var offset: Int? = 0
            var merged = emptyMap<String, FisisAsset>()
            while (offset != null) {
                val page = runCatching { api.ranking(sector, offset!!) }.getOrNull() ?: break
                merged = merged + page.assets
                _state.update { it.copy(assets = it.assets + (sector to merged)) }
                if (!page.ranked) break
                offset = page.next
            }
            _state.update { it.copy(ranking = false, assets = it.assets + (sector to merged)) }
        }
    }

    fun togglePick(company: FisisCompany) = _state.update {
        val picks = if (company in it.picks) it.picks - company else if (it.picks.size < MAX_COMPARE) it.picks + company else it.picks
        it.copy(picks = picks)
    }

    fun open(view: FisisView) = _state.update { it.copy(view = view) }
    fun back() = _state.update { it.copy(view = FisisView.Companies) }

    /** 회사 경영정보를 받아 둔다(이미 있으면 다시 받지 않는다). */
    fun ensure(sector: String, company: FisisCompany) {
        val key = profileKey(sector, company.code)
        val s = _state.value
        if (key in s.profiles || key in s.loading) return
        _state.update { it.copy(loading = it.loading + key, errors = it.errors - key) }
        viewModelScope.launch {
            runCatching { api.profile(sector, company.code) }
                .onSuccess { p -> _state.update { it.copy(profiles = it.profiles + (key to p), loading = it.loading - key) } }
                .onFailure { e -> _state.update { it.copy(loading = it.loading - key, errors = it.errors + (key to (e.message ?: "자료를 불러오지 못했습니다."))) } }
        }
    }

    fun retry(sector: String, company: FisisCompany) {
        _state.update { it.copy(errors = it.errors - profileKey(sector, company.code)) }
        ensure(sector, company)
    }
}

/** 금융사 주요 경영정보: 권역별로 총자산 순으로 회사를 찾고, 현황·재무정보·재무비율을 보고, 회사 2~3곳을 비교한다. */
@Composable
fun FisisScreen(viewModel: FisisViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.view != FisisView.Companies) { viewModel.back() }
    Column(modifier.fillMaxSize()) {
        when (val view = state.view) {
            FisisView.Companies -> {
                LargeTitle("금융사 주요 경영정보")
                if (!viewModel.isConfigured) CenterMessage("서버가 연결되지 않아 경영정보를 불러올 수 없습니다.")
                else CompanyList(state, viewModel)
            }
            is FisisView.Detail -> CompanyDetail(view.company, state, viewModel)
            FisisView.Compare -> CompareView(state, viewModel)
        }
    }
}

@Composable
private fun CompanyList(state: FisisUiState, viewModel: FisisViewModel) {
    val sector = FISIS_SECTORS.first { it.code == state.sector }
    var query by rememberSaveable { mutableStateOf("") }
    val all = state.companies[sector.code].orEmpty()
    val assets = state.assets[sector.code].orEmpty()
    // 총자산이 큰 순서. 총자산을 아직 못 받은 회사는 이름순으로 뒤에 둔다.
    val sorted = remember(all, assets) {
        all.sortedWith(compareByDescending<FisisCompany> { assets[it.code]?.let { a -> fisisWon(a.value, a.unit) } ?: Double.NEGATIVE_INFINITY }.thenBy { it.name })
    }
    val shown = remember(sorted, query) { sorted.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FISIS_SECTORS.forEach { s -> PillChip(s.label, onClick = { query = ""; viewModel.selectSector(s.code) }, selected = s.code == sector.code) }
        }
        SearchPill(
            value = query, onValueChange = { query = it }, placeholder = "${sector.label} 회사 이름 검색", onSearch = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), description = "금융회사 검색"
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.picks.isNotEmpty() -> "비교 선택 ${state.picks.size}/$MAX_COMPARE: " + state.picks.joinToString(", ") { it.name }
                    state.ranking -> "총자산 순위를 불러오는 중… 회사를 누르면 상세, 체크하면 비교"
                    else -> "총자산 큰 순 · 회사를 누르면 상세, 체크하면 비교(최대 $MAX_COMPARE 곳)"
                },
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (state.picks.size >= 2) TextButton(onClick = { viewModel.open(FisisView.Compare) }) { Text("비교하기") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadingCompanies && all.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && all.isEmpty() -> CenterMessage(state.error.orEmpty(), isError = true, actionLabel = "다시 시도", onAction = { viewModel.loadCompanies(sector.code, force = true) })
                shown.isEmpty() -> CenterMessage("검색 결과가 없습니다.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.code }) { company ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.open(FisisView.Detail(company)) }.padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                Text(company.name, style = MaterialTheme.typography.bodyLarge)
                                assets[company.code]?.let {
                                    Text("총자산 ${fisisValueText(it.value, it.unit)} (${fisisMonthLabel(it.month)})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Checkbox(checked = company in state.picks, onCheckedChange = { viewModel.togglePick(company) })
                        }
                        RowDivider()
                    }
                }
            }
        }
    }
}

/** 값 한 줄: 이름과 값, 그 아래에 출처(어느 통계표·계정인지)와 시점. */
@Composable
private fun MetricRow(label: String, value: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        }
        RowDivider()
    }
}

@Composable
private fun MetricRow(metric: FisisMetric, month: String?, showMonth: Boolean = false, method: String? = null) {
    val p = metric.at(month)
    val text = when {
        p == null -> "자료 없음"
        p.text != null -> p.text
        p.value != null -> fisisValueText(p.value, metric.unit)
        else -> "자료 없음"
    }
    val sub = buildString {
        if (showMonth && p != null) append(fisisMonthLabel(p.month)).append(" · ")
        append(metric.source)
        if (metric.column.isNotBlank()) append(" · ").append(metric.column)
        if (method != null) append("\n").append(method)
    }
    MetricRow(metric.label, text, sub)
}

@Composable
private fun SubTitle(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 시점 고르기 칩(최근 시점부터). */
@Composable
private fun PeriodPicker(months: List<String>, selected: String?, term: String, onSelect: (String) -> Unit) {
    if (months.isEmpty()) return
    Column {
        Text("시점 선택(${fisisTermLabel(term).ifBlank { "기준" }})", Modifier.padding(start = 20.dp, top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            months.forEach { m -> PillChip(fisisMonthLabel(m), onClick = { onSelect(m) }, selected = m == (selected ?: months.first()), onPanel = true) }
        }
    }
}

private fun monthsOf(metrics: List<FisisMetric>): List<String> = metrics.flatMap { m -> m.points.map { it.month } }.distinct().sortedDescending()

@Composable
private fun ColumnScope.CompanyDetail(company: FisisCompany, state: FisisUiState, viewModel: FisisViewModel) {
    val sectorCode = state.sector
    val sector = FISIS_SECTORS.first { it.code == sectorCode }
    val key = profileKey(sectorCode, company.code)
    LaunchedEffect(key) { viewModel.ensure(sectorCode, company) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var basisChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var financeMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var ratioMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val profile = state.profiles[key]

    BackHeader(company.name, onBack = viewModel::back)
    UnderlineTabs(listOf("현황", "재무정보", "재무비율"), tab, { tab = it })
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            key in state.loading || (profile == null && key !in state.errors) -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            key in state.errors -> CenterMessage(state.errors[key].orEmpty(), isError = true, actionLabel = "다시 시도", onAction = { viewModel.retry(sectorCode, company) })
            profile == null || profile.metrics.isEmpty() -> CenterMessage("이 회사는 ${sector.label} 경영정보 자료를 찾지 못했습니다.")
            else -> {
                val basis = basisChoice?.takeIf { it in profile.basisOptions } ?: profile.basisOptions.firstOrNull()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (tab) {
                        0 -> SummaryTab(profile, basis)
                        1 -> {
                            if (profile.basisOptions.size > 1) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    profile.basisOptions.forEach { b -> PillChip("$b 재무제표", onClick = { basisChoice = b; financeMonth = null }, selected = b == basis, onPanel = true) }
                                }
                            } else {
                                Note("연결 기준 자료는 FISIS에서 제공되지 않아 ${basis ?: "별도"}(개별) 기준으로 표시합니다.")
                            }
                            FinanceTab(profile, basis, financeMonth, onMonth = { financeMonth = it })
                        }
                        else -> RatioTab(profile, ratioMonth, onMonth = { ratioMonth = it })
                    }
                    Note("자료: 금융감독원 금융통계정보시스템(FISIS). 금액은 원본 단위를 억원·조원으로 환산해 표시합니다.")
                }
            }
        }
    }
}

@Composable
private fun SummaryTab(profile: FisisProfile, basis: String?) {
    val fin = profile.group("fin", basis)
    val latest = monthsOf(fin).firstOrNull()
    SubTitle("현황자료 · 최근 시점 ${latest?.let { fisisMonthLabel(it) + " (" + fisisTermLabel(profile.term) + ")" } ?: "자료 없음"}")
    SUMMARY_KEYS.forEach { k ->
        val m = fin.firstOrNull { it.key == k }
        if (m != null) MetricRow(m, latest) else MetricRow(FINANCE_LABELS.getValue(k), "자료 없음", "FISIS에서 찾지 못했습니다")
    }
    val funds = profile.group("fund").filter { it.key == "fund_all" || it.key == "nav_all" }
    if (funds.isNotEmpty()) {
        SubTitle("펀드 현황")
        funds.forEach { MetricRow(it, null, showMonth = true) }
    }
    val ratios = profile.group("ratio")
    SubTitle("핵심 감독지표")
    if (ratios.isEmpty()) Note("이 권역의 감독지표를 FISIS에서 찾지 못했습니다.")
    ratios.forEach { MetricRow(it, null, showMonth = true) }
    SubTitle("회사 정보")
    listOf("ceo" to "대표이사", "holder" to "최대주주", "holderPct" to "최대주주 지분율").forEach { (k, label) ->
        val m = profile.metric(k)
        if (m != null) MetricRow(m, null) else MetricRow(label, "FISIS 미제공")
    }
}

private val FINANCE_LABELS = mapOf(
    "assets" to "총자산", "liab" to "총부채", "equity" to "자기자본", "capital" to "자본금",
    "revenue" to "영업수익", "expense" to "영업비용", "netincome" to "당기순이익"
)

@Composable
private fun FinanceTab(profile: FisisProfile, basis: String?, month: String?, onMonth: (String) -> Unit) {
    val fin = profile.group("fin", basis)
    val funds = profile.group("fund")
    val months = monthsOf(fin + funds)
    val selected = month?.takeIf { it in months } ?: months.firstOrNull()
    PeriodPicker(months, selected, profile.term, onMonth)
    SubTitle("주요 재무정보${basis?.let { " ($it)" }.orEmpty()}")
    FINANCE_KEYS.forEach { k ->
        val m = fin.firstOrNull { it.key == k }
        if (m != null) MetricRow(m, selected) else MetricRow(FINANCE_LABELS.getValue(k), "자료 없음", "FISIS에서 찾지 못했습니다")
    }
    if (funds.isNotEmpty()) {
        SubTitle("펀드 설정원본 · 순자산")
        funds.forEach { MetricRow(it, selected) }
    }
}

@Composable
private fun RatioTab(profile: FisisProfile, month: String?, onMonth: (String) -> Unit) {
    val ratios = profile.group("ratio")
    val months = monthsOf(ratios)
    val selected = month?.takeIf { it in months } ?: months.firstOrNull()
    PeriodPicker(months, selected, ratios.firstOrNull()?.term ?: profile.term, onMonth)
    SubTitle("주요 재무비율(감독지표)")
    if (ratios.isEmpty()) Note("이 권역의 재무비율을 FISIS에서 찾지 못했습니다.")
    ratios.forEach { MetricRow(it, selected, method = FISIS_RATIO_METHODS[it.key]?.let { t -> "산정: $t" }) }
}

/** 회사 2~3곳의 현황을 한 표로 비교한다(각 회사의 최근 시점 기준). */
@Composable
private fun ColumnScope.CompareView(state: FisisUiState, viewModel: FisisViewModel) {
    val sectorCode = state.sector
    val picks = state.picks
    LaunchedEffect(picks) { picks.forEach { viewModel.ensure(sectorCode, it) } }
    val keys = picks.map { profileKey(sectorCode, it.code) }
    val pending = keys.any { it in state.loading || (it !in state.profiles && it !in state.errors) }
    val failed = keys.firstOrNull { it in state.errors }
    val profiles = picks.map { state.profiles[profileKey(sectorCode, it.code)] }

    BackHeader("회사 비교", onBack = viewModel::back)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            failed != null -> CenterMessage(state.errors[failed].orEmpty(), isError = true, actionLabel = "다시 시도", onAction = { picks.forEach { viewModel.retry(sectorCode, it) } })
            pending -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)) {
                    Text("", Modifier.weight(1.1f))
                    picks.forEach { Text(it.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, maxLines = 2) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                    Text("기준 시점", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    profiles.forEach { p ->
                        val m = p?.group("fin", p.basisOptions.firstOrNull())?.let(::monthsOf)?.firstOrNull()
                        Text(m?.let { fisisMonthLabel(it) } ?: "-", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val ratioKeys = profiles.filterNotNull().flatMap { p -> p.group("ratio").map { it.key to it.label } }.distinct()
                val rows = SUMMARY_KEYS.map { it to FINANCE_LABELS.getValue(it) } + ratioKeys
                rows.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium)
                        profiles.forEach { p ->
                            val m = p?.metric(k, p.basisOptions.firstOrNull())
                            val v = m?.at(null)?.value
                            val text = if (m != null && v != null) fisisValueText(v, m.unit) else "-"
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                        }
                    }
                    RowDivider()
                }
                Note("각 회사의 FISIS 최근 시점 기준입니다. 시점이 다르면 위의 기준 시점을 확인하세요.")
            }
        }
    }
}
