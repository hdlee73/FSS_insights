package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.FISIS_SECTORS
import io.github.hdlee73.financenewsradar.data.FisisApi
import io.github.hdlee73.financenewsradar.data.FisisAsset
import io.github.hdlee73.financenewsradar.data.FisisCompany
import io.github.hdlee73.financenewsradar.data.FisisMetric
import io.github.hdlee73.financenewsradar.data.FisisProfile
import io.github.hdlee73.financenewsradar.data.fisisHomepage
import io.github.hdlee73.financenewsradar.data.fisisMonthLabel
import io.github.hdlee73.financenewsradar.data.fisisTermLabel
import io.github.hdlee73.financenewsradar.data.fisisValueText
import io.github.hdlee73.financenewsradar.data.fisisWon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DATA_NOTICE = "원본 오류로 인해 일부 수치가 잘못 표시되어 있을 수 있습니다."

private const val FISIS_SOURCE = "자료출처: 금융감독원 금융통계정보시스템"

/** 비교에 담을 수 있는 회사 수. */
private const val MAX_COMPARE = 3

private val FINANCE_LABELS = mapOf(
    "assets" to "자산총계", "liab" to "부채총계", "equity" to "자기자본",
    "revenue" to "영업수익", "expense" to "영업비용", "opinc" to "영업이익", "netincome" to "당기순이익"
)

/** 증권사 목록에서 외국계(외국 증권사 서울지점·한국법인)를 가려내는 이름 조각. */
private val FOREIGN_BROKER = Regex("지점|골드만|모간스탠리|모건스탠리|메릴린치|비엔피|BNP|씨엘에스에이|CLSA|크레디|노무라|맥쿼리|유비에스|UBS|제이피모간|JP모간|도이치|소시에테|나틱시스|다이와|미즈호|HSBC|에이치에스비씨|바클레이|스탠다드차타드|씨티|ING|아이엔지|SMBC|라자드|이스트스프링|아이씨엠|ICM|CMS|씨엠에스|에스지|SG|CGS|CIMB|씨아이엠비|글로벌마켓|알비에스|RBS|스미토모|미쓰비시|도쿄|SBI증권")

/** 비교표에 넣는 재무 지표. */

/** 권역별 주요경영지표(키 → 이름). 자료가 없으면 "-"로 보여 준다. 자산운용사는 제공하지 않는다. */
private val RATIO_ROWS: Map<String, List<Pair<String, String>>> = mapOf(
    "F" to listOf("ncr" to "순자본비율(개별)", "ncr_c" to "순자본비율(연결)", "lev" to "레버리지비율", "liq" to "유동성비율"),
    "A" to listOf("bis" to "BIS기준 자기자본비율", "delinq" to "총대출채권 연체율", "npl" to "고정이하여신비율", "lcr" to "유동성커버리지비율"),
    "H" to listOf("solv" to "지급여력비율"),
    "I" to listOf("solv" to "지급여력비율"),
    "E" to listOf("bis" to "BIS자기자본비율", "npl" to "고정이하여신비율", "delinq" to "연체율", "liq" to "유동성비율"),
    "C" to listOf("adj" to "조정자기자본비율", "npl" to "고정이하채권비율")
)

/** 재무 시점은 2024년 이후만 고를 수 있다. */
private const val FIRST_MONTH = "202401"

private val BASES = listOf("별도", "연결")

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

/** 금융회사 주요 현황: 권역별로 자산총계 순으로 회사를 찾고, 재무현황·주요경영지표를 보고, 회사 2~3곳을 비교한다. */
@Composable
fun FisisScreen(viewModel: FisisViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.view != FisisView.Companies) { viewModel.back() }
    Column(modifier.fillMaxSize()) {
        when (val view = state.view) {
            FisisView.Companies -> {
                LargeTitle("금융회사 주요 현황", subtitle = "회사명으로 검색해 경영지표를 비교")
                Text(FISIS_SOURCE, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // 증권사는 국내 증권사를 먼저, 그다음 외국계를 각각 총자산 큰 순으로 둔다.
    val sorted = remember(all, assets, sector.code) {
        all.sortedWith(
            compareBy<FisisCompany> { if (sector.code == "F" && FOREIGN_BROKER.containsMatchIn(it.name)) 1 else 0 }
                .thenByDescending { assets[it.code]?.let { a -> fisisWon(a.value, a.unit) } ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.name }
        )
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
                    state.ranking -> "순위를 불러오는 중… 회사를 누르면 상세, 체크하면 비교"
                    else -> "회사를 누르면 상세, 체크하면 비교(최대 $MAX_COMPARE 곳) · $DATA_NOTICE"
                },
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis
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
private fun MetricRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        }
        RowDivider()
    }
}

@Composable
private fun MetricRow(label: String, metric: FisisMetric?, month: String?) {
    val p = metric?.at(month)
    val text = if (p?.value != null && metric != null) fisisValueText(p.value, metric.unit) else "-"
    MetricRow(label, text)
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

private fun monthsOf(metrics: List<FisisMetric>): List<String> = metrics.flatMap { m -> m.points.map { it.month } }.filter { it >= FIRST_MONTH }.distinct().sortedDescending()

@Composable
private fun ColumnScope.CompanyDetail(company: FisisCompany, state: FisisUiState, viewModel: FisisViewModel) {
    val sectorCode = state.sector
    val sector = FISIS_SECTORS.first { it.code == sectorCode }
    val key = profileKey(sectorCode, company.code)
    LaunchedEffect(key) { viewModel.ensure(sectorCode, company) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var basis by rememberSaveable { mutableStateOf(BASES.first()) }
    var financeMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var ratioMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val profile = state.profiles[key]

    BackHeader(company.name, onBack = viewModel::back)
    UnderlineTabs(listOf("재무현황", "주요경영지표"), tab, { tab = it })
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            key in state.loading || (profile == null && key !in state.errors) -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            key in state.errors -> CenterMessage(state.errors[key].orEmpty(), isError = true, actionLabel = "다시 시도", onAction = { viewModel.retry(sectorCode, company) })
            profile == null || profile.metrics.isEmpty() -> CenterMessage("이 회사는 ${sector.label} 자료를 찾지 못했습니다.")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (tab == 0) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BASES.forEach { b -> PillChip("$b 재무제표", onClick = { basis = b; financeMonth = null }, selected = b == basis, onPanel = true) }
                    }
                    FinanceTab(profile, basis, financeMonth, onMonth = { financeMonth = it })
                } else {
                    RatioTab(sectorCode, profile, ratioMonth, onMonth = { ratioMonth = it })
                }
                if (tab == 0) HomepageButton(company)
                Note("$FISIS_SOURCE. 금액은 억원 단위입니다. $DATA_NOTICE")
            }
        }
    }
}

/** 금액 글자에서 단위("억원")를 뗀 숫자만(단위는 섹션 제목 옆에 한 번만 보여 준다). */
private fun amountOnly(metric: FisisMetric?, month: String?): String? {
    if (metric == null) return null
    val v = metric.at(month)?.value ?: return null
    return fisisValueText(v, metric.unit).removeSuffix("억원")
}

@Composable
private fun SectionHeader(title: String, unit: String? = "단위: 억원") {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (unit != null) Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 재무상태 카드: 이름은 왼쪽, 숫자는 오른쪽 정렬. */
@Composable
private fun BalanceCard(label: String, value: String, highlight: Boolean, modifier: Modifier) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Column(modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            value, Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, maxLines = 1, color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 손익 표의 한 줄: 항목, 당분기, 누적(손실은 빨간색). */
@Composable
private fun PlRow(label: String, current: FisisMetric?, cumulative: FisisMetric?, month: String?, bold: Boolean = false) {
    @Composable
    fun Cell(metric: FisisMetric?, modifier: Modifier) {
        val v = metric?.at(month)?.value
        Text(
            amountOnly(metric, month) ?: "-", modifier, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (v != null && v < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Cell(current, Modifier.weight(1f))
        Cell(cumulative, Modifier.weight(1f))
    }
}

@Composable
private fun FinanceTab(profile: FisisProfile, basis: String, month: String?, onMonth: (String) -> Unit) {
    val fin = profile.group("fin", basis).filter { it.basis == basis }
    val funds = profile.group("fund")
    val months = monthsOf(fin + funds)
    val selected = month?.takeIf { it in months } ?: months.firstOrNull()
    PeriodPicker(months, selected, profile.term, onMonth)
    if (fin.isEmpty()) Note("$basis 재무제표 자료가 없습니다.")
    fun metric(key: String) = fin.firstOrNull { it.key == key }

    SectionHeader("재무상태 ($basis)")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("assets", "liab", "equity").forEach { k ->
            BalanceCard(FINANCE_LABELS.getValue(k), amountOnly(metric(k), selected) ?: "-", highlight = k == "assets", modifier = Modifier.weight(1f))
        }
    }
    // 부채·자기자본 비중 막대
    val liab = metric("liab")?.at(selected)?.value
    val equity = metric("equity")?.at(selected)?.value
    if (liab != null && equity != null && liab > 0 && equity > 0) {
        val liabShare = (liab / (liab + equity)).toFloat().coerceIn(0.02f, 0.98f)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).height(8.dp).clip(RoundedCornerShape(4.dp))) {
            Box(Modifier.weight(liabShare).height(8.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
            Box(Modifier.weight(1f - liabShare).height(8.dp).background(MaterialTheme.colorScheme.primary))
        }
        Text(
            String.format(java.util.Locale.US, "■ 부채 %.1f%%   ■ 자기자본 %.1f%%", liabShare * 100, (1f - liabShare) * 100),
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SectionHeader("손익현황 ($basis)")
    val current = when (profile.term) { "H" -> "당반기"; "Y" -> "당기"; else -> "당분기" }
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("항목", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(current, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            Text("누적", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        listOf("revenue", "expense", "opinc", "netincome").forEachIndexed { i, k ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlRow(FINANCE_LABELS.getValue(k), metric(k), metric("${k}_cum"), selected, bold = k == "netincome")
        }
    }
    if (funds.isNotEmpty()) {
        SubTitle("펀드 설정원본 · 순자산")
        funds.forEach { MetricRow(it.label, it, selected) }
    }
}

@Composable
private fun RatioTab(sector: String, profile: FisisProfile, month: String?, onMonth: (String) -> Unit) {
    val rows = RATIO_ROWS[sector]
    if (rows == null) {
        Note("이 권역은 주요경영지표를 제공하지 않습니다.")
        return
    }
    val ratios = profile.group("ratio")
    val months = monthsOf(ratios)
    val selected = month?.takeIf { it in months } ?: months.firstOrNull()
    PeriodPicker(months, selected, ratios.firstOrNull()?.term ?: profile.term, onMonth)
    var span by rememberSaveable { mutableIntStateOf(4) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(4, 8).forEach { n -> PillChip("최근 ${n}${fisisTermLabel(profile.term).ifBlank { "개 시점" }}", onClick = { span = n }, selected = span == n, onPanel = true) }
    }
    SectionHeader("주요경영지표", unit = "단위: %")
    // 선택한 시점(기본은 최근 시점)을 맨 왼쪽 열로 두고, 이전 시점을 오른쪽에 최신순으로 놓는다.
    val start = months.indexOf(selected).coerceAtLeast(0)
    val shown = months.drop(start).take(span)
    RatioTable(rows, ratios, shown, selected)
}

@Composable
private fun RatioTable(rows: List<Pair<String, String>>, ratios: List<FisisMetric>, months: List<String>, selected: String?) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val labelW = 116.dp
    val cellW = 76.dp
    val hl = MaterialTheme.colorScheme.primaryContainer
    @Composable
    fun Cell(text: String, m: String?, bold: Boolean, header: Boolean) {
        val on = m != null && m == selected
        Text(
            text, Modifier.width(cellW).background(if (on) hl else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 6.dp, vertical = if (header) 8.dp else 12.dp),
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (on) FontWeight.Bold else if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End, maxLines = 1
        )
    }
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, outline, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), verticalAlignment = Alignment.CenterVertically) {
            Text("지표", Modifier.width(labelW).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            months.forEach { Cell(fisisMonthLabel(it), it, bold = false, header = true) }
        }
        rows.forEach { (k, label) ->
            HorizontalDivider(color = outline)
            val metric = ratios.firstOrNull { it.key == k }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(labelW).padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                months.forEach { m ->
                    val v = metric?.at(m)?.value
                    Cell(if (v != null && metric != null) fisisValueText(v, metric.unit).removeSuffix("%") else "-", m, bold = false, header = false)
                }
            }
        }
    }
    Note("가로로 밀어 다른 시점을 볼 수 있습니다. 선택한 시점 열은 강조됩니다.")
}

@Composable
private fun HomepageButton(company: FisisCompany) {
    val home = fisisHomepage(company.name)
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(home.url) } }, Modifier.padding(horizontal = 12.dp)) {
        Text(if (home.known) "홈페이지 열기 (${home.url.removePrefix("https://")})" else "공식 홈페이지 검색하기")
    }
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
                val outline = MaterialTheme.colorScheme.outlineVariant
                // 회사 이름 머리글
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1.1f))
                    picks.forEach {
                        Text(
                            it.name, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 6.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("기준 시점", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    profiles.forEach { p ->
                        val m = p?.group("fin", "별도")?.let(::monthsOf)?.firstOrNull()
                        Text(m?.let { fisisMonthLabel(it) } ?: "-", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                @Composable
                fun Section(title: String, unit: String?, rows: List<Triple<String, String, Boolean>>) {
                    SectionHeader(title, unit)
                    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, outline, RoundedCornerShape(16.dp))) {
                        rows.forEachIndexed { i, (k, label, pct) ->
                            if (i > 0) HorizontalDivider(color = outline)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium)
                                profiles.forEach { p ->
                                    val m = p?.metric(k, "별도")
                                    val v = m?.at(null)?.value
                                    val text = if (m != null && v != null) fisisValueText(v, m.unit).removeSuffix(if (pct) "%" else "억원") else "-"
                                    Text(
                                        text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                                        color = if (v != null && v < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
                Section("재무상태", "단위: 억원", listOf("assets", "liab", "equity").map { Triple(it, FINANCE_LABELS.getValue(it), false) })
                // 손익 항목은 누적 값으로 비교한다.
                Section("손익현황 (누적)", "단위: 억원", listOf("revenue", "opinc", "netincome").map { Triple("${it}_cum", FINANCE_LABELS.getValue(it) + " (누적)", false) })
                val ratioKeys = RATIO_ROWS[sectorCode].orEmpty()
                if (ratioKeys.isNotEmpty()) Section("주요경영지표", "단위: %", ratioKeys.map { (k, label) -> Triple(k, label, true) })
                Note("각 회사의 FISIS 최근 시점 기준입니다. 시점이 다르면 위의 기준 시점을 확인하세요.")
            }
        }
    }
}
