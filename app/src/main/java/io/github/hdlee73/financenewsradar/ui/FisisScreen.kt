package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.FISIS_SECTORS
import io.github.hdlee73.financenewsradar.data.FisisAccount
import io.github.hdlee73.financenewsradar.data.FisisApi
import io.github.hdlee73.financenewsradar.data.FisisCompany
import io.github.hdlee73.financenewsradar.data.FisisData
import io.github.hdlee73.financenewsradar.data.FisisSector
import io.github.hdlee73.financenewsradar.data.fisisMonthLabel
import io.github.hdlee73.financenewsradar.data.fisisValueText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 비교에 담을 수 있는 회사 수. */
private const val MAX_COMPARE = 3

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
    val picks: List<FisisCompany> = emptyList(),
    val view: FisisView = FisisView.Companies,
    /** "회사코드|통계표" → 조회 결과. */
    val data: Map<String, FisisData> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val error: String? = null
)

private fun dataKey(company: String, listNo: String) = "$company|$listNo"

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
                .onSuccess { list -> _state.update { it.copy(companies = it.companies + (sector to list), loadingCompanies = false) } }
                .onFailure { e -> _state.update { it.copy(loadingCompanies = false, error = e.message ?: "금융회사 목록을 불러오지 못했습니다.") } }
        }
    }

    fun togglePick(company: FisisCompany) = _state.update {
        val picks = if (company in it.picks) it.picks - company else if (it.picks.size < MAX_COMPARE) it.picks + company else it.picks
        it.copy(picks = picks)
    }

    fun open(view: FisisView) = _state.update { it.copy(view = view) }
    fun back() = _state.update { it.copy(view = FisisView.Companies) }

    /** 회사·통계표 자료를 받아 둔다(이미 있으면 다시 받지 않는다). */
    fun ensure(company: FisisCompany, listNo: String) {
        val key = dataKey(company.code, listNo)
        val s = _state.value
        if (key in s.data || key in s.loading) return
        _state.update { it.copy(loading = it.loading + key, errors = it.errors - key) }
        viewModelScope.launch {
            runCatching { api.data(company.code, listNo) }
                .onSuccess { d -> _state.update { it.copy(data = it.data + (key to d), loading = it.loading - key) } }
                .onFailure { e -> _state.update { it.copy(loading = it.loading - key, errors = it.errors + (key to (e.message ?: "자료를 불러오지 못했습니다."))) } }
        }
    }

    fun retry(company: FisisCompany, listNo: String) {
        _state.update { it.copy(errors = it.errors - dataKey(company.code, listNo)) }
        ensure(company, listNo)
    }
}

private val seriesColors = listOf(Color(0xFFE2622B), Color(0xFF2B7DE2), Color(0xFF2BA36B))

/** 금융회사 경영정보: 권역을 고르고 회사를 찾아 FISIS 통계(재무·건전성)를 보고, 회사 2~3곳을 비교한다. */
@Composable
fun FisisScreen(viewModel: FisisViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.view != FisisView.Companies) { viewModel.back() }
    Column(modifier.fillMaxSize()) {
        when (val view = state.view) {
            FisisView.Companies -> {
                LargeTitle("금융회사 경영정보")
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
    val shown = remember(all, query) { all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) } }
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
                if (state.picks.isEmpty()) "회사를 누르면 상세, 체크하면 비교(최대 $MAX_COMPARE 곳)" else "비교 선택 ${state.picks.size}/$MAX_COMPARE: " + state.picks.joinToString(", ") { it.name },
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
                            Text(company.name, Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
                            Checkbox(checked = company in state.picks, onCheckedChange = { viewModel.togglePick(company) })
                        }
                        RowDivider()
                    }
                }
            }
        }
    }
}

/** 통계표 선택 칩 + 값 열 선택(열이 둘 이상일 때). */
@Composable
private fun TablePicker(sector: FisisSector, tableIndex: Int, onTable: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sector.tables.forEachIndexed { i, t -> PillChip(t.label, onClick = { onTable(i) }, selected = i == tableIndex) }
    }
}

private fun termLabel(term: String) = when (term) { "Q" -> "분기"; "H" -> "반기"; "Y" -> "연간"; else -> "" }

@Composable
private fun ColumnScope.CompanyDetail(company: FisisCompany, state: FisisUiState, viewModel: FisisViewModel) {
    val sector = FISIS_SECTORS.first { it.code == state.sector }
    var tableIndex by rememberSaveable { mutableStateOf(0) }
    var openAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var columnId by rememberSaveable { mutableStateOf<String?>(null) }
    val table = sector.tables[tableIndex.coerceIn(sector.tables.indices)]
    val key = dataKey(company.code, table.listNo)
    LaunchedEffect(key) { viewModel.ensure(company, table.listNo) }
    val data = state.data[key]
    val column = (data?.columns?.firstOrNull { it.id == columnId } ?: data?.columns?.firstOrNull())?.id ?: "a"

    BackHeader(company.name, onBack = viewModel::back)
    TablePicker(sector, tableIndex) { tableIndex = it; openAccount = null; columnId = null }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            key in state.loading || (data == null && key !in state.errors) -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            key in state.errors -> CenterMessage(state.errors[key].orEmpty(), isError = true, actionLabel = "다시 시도", onAction = { viewModel.retry(company, table.listNo) })
            data == null || data.isEmpty -> CenterMessage("이 회사는 '${table.label}' 자료가 없습니다.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    if (data.columns.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            data.columns.forEach { c -> PillChip(c.name.ifBlank { c.id }, onClick = { columnId = c.id }, selected = c.id == column, onPanel = true) }
                        }
                    }
                    Text(
                        "${termLabel(data.term)} 기준 · ${table.label} (${table.listNo}) · 값은 FISIS 원자료 단위(금액은 대개 백만원)",
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(data.accounts.filter { it.latest(column) != null }, key = { it.code + it.name }) { account ->
                    AccountRow(account, column, expanded = openAccount == account.code) {
                        openAccount = if (openAccount == account.code) null else account.code
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: FisisAccount, column: String, expanded: Boolean, onClick: () -> Unit) {
    val latest = account.latest(column) ?: return
    val change = account.change(column)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fisisMonthLabel(latest.month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fisisValueText(latest.values[column]!!, account.isRatio), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (change != null) {
                    Text(
                        (if (change > 0) "▲ " else if (change < 0) "▼ " else "") + fisisValueText(kotlin.math.abs(change), account.isRatio),
                        style = MaterialTheme.typography.labelSmall, color = changeColor(change)
                    )
                }
            }
        }
        if (expanded) {
            val pts = account.points.filter { it.values[column] != null }
            TrendChart(listOf(pts.map { it.month to it.values[column]!! }), listOf(seriesColors[0]), Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fisisMonthLabel(pts.first().month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fisisMonthLabel(pts.last().month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                pts.asReversed().take(8).forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fisisMonthLabel(p.month), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fisisValueText(p.values[column]!!, account.isRatio), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        RowDivider()
    }
}

/** 시점(YYYYMM)별 값 선 그래프. 시리즈 여러 개를 겹쳐 그린다(같은 시점은 같은 x 위치). */
@Composable
private fun TrendChart(series: List<List<Pair<String, Double>>>, colors: List<Color>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val months = series.flatMap { s -> s.map { it.first } }.distinct().sorted()
        val values = series.flatMap { s -> s.map { it.second } }
        if (months.size < 2 || values.isEmpty()) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 6.dp.toPx()
        fun x(m: String) = pad + months.indexOf(m) / (months.size - 1f) * (size.width - 2 * pad)
        fun y(v: Double) = pad + (size.height - 2 * pad) * (1f - ((v - lo) / span).toFloat())
        series.forEachIndexed { i, s ->
            if (s.isEmpty()) return@forEachIndexed
            val color = colors[i % colors.size]
            val path = Path()
            s.sortedBy { it.first }.forEachIndexed { j, (m, v) -> if (j == 0) path.moveTo(x(m), y(v)) else path.lineTo(x(m), y(v)) }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val last = s.maxByOrNull { it.first }!!
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(last.first), y(last.second)))
        }
    }
}

@Composable
private fun ColumnScope.CompareView(state: FisisUiState, viewModel: FisisViewModel) {
    val sector = FISIS_SECTORS.first { it.code == state.sector }
    val picks = state.picks
    var tableIndex by rememberSaveable { mutableStateOf(0) }
    var accountCode by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    val table = sector.tables[tableIndex.coerceIn(sector.tables.indices)]
    LaunchedEffect(picks, table.listNo) { picks.forEach { viewModel.ensure(it, table.listNo) } }
    val results = picks.map { it to state.data[dataKey(it.code, table.listNo)] }
    val pending = picks.any { dataKey(it.code, table.listNo) in state.loading || (state.data[dataKey(it.code, table.listNo)] == null && dataKey(it.code, table.listNo) !in state.errors) }
    val failed = picks.firstOrNull { dataKey(it.code, table.listNo) in state.errors }
    val loaded = results.mapNotNull { it.second }.filter { !it.isEmpty }
    // 비교할 계정: 첫 회사의 계정 목록 기준(없으면 가장 많이 겹치는 첫 계정).
    val accounts = loaded.firstOrNull()?.accounts.orEmpty().filter { a -> loaded.all { d -> d.accounts.any { it.code == a.code } } }
    val account = accounts.firstOrNull { it.code == accountCode } ?: accounts.firstOrNull()

    BackHeader("회사 비교", onBack = viewModel::back)
    TablePicker(sector, tableIndex) { tableIndex = it; accountCode = null }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            failed != null -> CenterMessage(state.errors[dataKey(failed.code, table.listNo)].orEmpty(), isError = true, actionLabel = "다시 시도", onAction = { picks.forEach { viewModel.retry(it, table.listNo) } })
            pending -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            account == null -> CenterMessage("선택한 회사들에 함께 있는 '${table.label}' 항목이 없습니다.")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val column = loaded.first().columns.firstOrNull()?.id ?: "a"
                Row(Modifier.fillMaxWidth().clickable { pickerOpen = true }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("비교 항목", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    }
                    Text("변경", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                val lines = results.map { (company, data) ->
                    company to data?.accounts?.firstOrNull { it.code == account.code }?.points?.filter { it.values[column] != null }.orEmpty()
                }
                TrendChart(lines.map { (_, pts) -> pts.map { it.month to it.values[column]!! } }, seriesColors, Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 20.dp))
                lines.forEachIndexed { i, (company, pts) ->
                    val last = pts.lastOrNull()
                    val prev = pts.getOrNull(pts.size - 2)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(seriesColors[i % seriesColors.size], CircleShape))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(company.name, style = MaterialTheme.typography.bodyLarge)
                            if (last != null) Text(fisisMonthLabel(last.month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (last == null) Text("자료 없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Column(horizontalAlignment = Alignment.End) {
                            Text(fisisValueText(last.values[column]!!, account.isRatio), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            val change = prev?.let { last.values[column]!! - it.values[column]!! }
                            if (change != null) Text((if (change > 0) "▲ " else if (change < 0) "▼ " else "") + fisisValueText(kotlin.math.abs(change), account.isRatio), style = MaterialTheme.typography.labelSmall, color = changeColor(change))
                        }
                    }
                    RowDivider()
                }
                Text("값은 FISIS 원자료 단위(금액은 대개 백만원)이며, 직전 시점 대비 증감입니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("닫기") } },
            title = { Text("비교 항목 선택") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(accounts, key = { it.code + it.name }) { a ->
                        Text(
                            a.name,
                            Modifier.fillMaxWidth().clickable { accountCode = a.code; pickerOpen = false }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (a.code == account?.code) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        )
    }
}

