package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.SanctionCase
import io.github.hdlee73.financenewsradar.data.SanctionDataset
import io.github.hdlee73.financenewsradar.data.SanctionExport
import io.github.hdlee73.financenewsradar.data.SanctionFilter
import io.github.hdlee73.financenewsradar.data.SanctionPeriod
import io.github.hdlee73.financenewsradar.data.SanctionRepository
import io.github.hdlee73.financenewsradar.data.SanctionItem
import io.github.hdlee73.financenewsradar.data.SanctionNames
import io.github.hdlee73.financenewsradar.data.SanctionSearchField
import io.github.hdlee73.financenewsradar.data.SanctionText
import io.github.hdlee73.financenewsradar.data.SanctionSectors
import io.github.hdlee73.financenewsradar.data.SanctionStats
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class SanctionUiState(
    val data: SanctionDataset? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val filter: SanctionFilter = SanctionFilter(),
    val stats: Boolean = false
)

class SanctionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SanctionRepository(application)
    private val _state = MutableStateFlow(SanctionUiState())
    val state: StateFlow<SanctionUiState> = _state.asStateFlow()

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

    fun setFilter(f: SanctionFilter) = _state.update { it.copy(filter = f) }
    fun showStats(on: Boolean) = _state.update { it.copy(stats = on) }
}

private val sDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/**
 * 서랍 메뉴 "금융회사 제재현황": 2011년 이후 금감원 제재공시 전체 목록(공시마다 조치·지적제목·위반 조항과 [원문보기])과 통계.
 * [onOpenLaw]는 "보험업법 제95조의2" 같은 조문을 스마트 법령검색에서 바로 연다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SanctionScreen(vm: SanctionViewModel, onOpenLaw: (String) -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensure() }
    val data = state.data
    val filter = state.filter

    val rows = remember(data, filter) { if (data == null) emptyList() else SanctionStats.filter(data, filter) }
    val cases = remember(rows) { SanctionStats.cases(rows) }
    val rowsByCase = remember(rows) { rows.groupBy { it.case.id } }
    val summary = remember(rows, data) { SanctionStats.summarize(rows, data?.years.orEmpty(), filter) }

    if (state.stats) BackHandler { vm.showStats(false) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        SanctionExport.write(out, rows, summary, scopeLabel(filter, data), data.generatedAt)
                    } ?: error("파일을 열 수 없습니다.")
                }
            }
            Toast.makeText(context, if (ok.isSuccess) "엑셀로 저장했습니다 (${rows.size}행)" else "저장하지 못했습니다: ${ok.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
    fun export() {
        if (data == null || rows.isEmpty()) {
            Toast.makeText(context, "저장할 내역이 없습니다.", Toast.LENGTH_SHORT).show(); return
        }
        val period = filter.periodLabel(data.fromYear).replace(Regex("[^0-9A-Za-z가-힣.~]"), "").replace("~", "-")
        saver.launch("제재현황_${period}_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.xlsx")
    }
    val fromYear = data?.fromYear ?: SanctionDataset.FIRST_YEAR

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "title") {
                LargeTitle("금융회사 제재현황", subtitle = "${fromYear}년 이후 금감원 제재공시와 통계") {
                    IconButton(onClick = ::export, enabled = data != null) { Icon(Icons.Default.Download, contentDescription = "엑셀로 저장") }
                    IconButton(onClick = { vm.load(force = true) }, enabled = !state.loading) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
                }
            }
            item(key = "tabs") { UnderlineTabs(listOf("제재 공시", "통계"), if (state.stats) 1 else 0, { vm.showStats(it == 1) }) }
            item(key = "field") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("검색", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SanctionSearchField.entries.forEach { f ->
                        PillChip(f.label, onClick = { vm.setFilter(filter.copy(field = f)) }, selected = filter.field == f)
                    }
                }
            }
            item(key = "search") {
                SearchPill(
                    value = filter.query,
                    onValueChange = { vm.setFilter(filter.copy(query = it)) },
                    placeholder = filter.field.hint,
                    onSearch = { keyboard?.hide(); focus.clearFocus() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    description = "제재 공시 검색어"
                )
            }
            item(key = "filters") { FilterBar(data, filter) { vm.setFilter(it) } }
            when {
                data == null && state.loading -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text("${fromYear}년 이후 제재공시를 받는 중입니다…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                data == null -> item(key = "error") {
                    CenterMessage(state.error ?: "자료가 없습니다.", isError = true, actionLabel = "다시 시도", onAction = { vm.load(force = true) })
                }
                state.stats -> {
                    item(key = "stats") {
                        StatsPane(data, filter, summary, onFilter = { vm.setFilter(it); vm.showStats(false) }, onOpenLaw = onOpenLaw, onExport = ::export)
                    }
                }
                else -> {
                    item(key = "count") {
                        SectionLabel("공시 ${"%,d".format(cases.size)}건 · 지적 ${"%,d".format(summary.itemCount)}건 · ${filter.periodLabel(fromYear)}") {
                            TextButton(onClick = ::export) {
                                Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("엑셀 저장")
                            }
                        }
                    }
                    if (cases.isEmpty()) item(key = "empty") { CenterMessage("조건에 맞는 공시가 없습니다.") }
                    items(cases, key = { it.id }) { c ->
                        val matched = if (filter.query.isBlank() || filter.field == SanctionSearchField.COMPANY) emptySet()
                            else rowsByCase[c.id].orEmpty().mapNotNull { it.item }.toSet()
                        SanctionCaseCard(c, matched, onOpenLaw)
                    }
                    item(key = "notice") {
                        Text(
                            "자료 기준 ${data.generatedAt.take(16).replace('T', ' ')} · 금감원 공시를 자동으로 정리한 참고용 자료입니다.",
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
        ScrollToTopButton(listState)
    }
}

/** 금융회사 주요 현황의 '최근 제재내역' 탭: 그 회사의 제재공시(최신순). 회사 이름으로 맞춘다. */
@Composable
fun CompanySanctionPane(company: String, vm: SanctionViewModel, onOpenLaw: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensure() }
    val data = state.data
    val cases = remember(data, company) { if (data == null) emptyList() else SanctionNames.casesOf(data, company) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp)) {
        when {
            data == null && state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) }
            }
            data == null -> item {
                CenterMessage(state.error ?: "자료가 없습니다.", isError = true, actionLabel = "다시 시도", onAction = { vm.load(force = true) })
            }
            cases.isEmpty() -> item {
                CenterMessage("${data.fromYear}년 이후 금감원 제재공시에서 '$company' 이름으로 된 공시를 찾지 못했습니다.\n(회사명이 바뀌었으면 '금융회사 제재현황'에서 옛 이름으로 검색해 보세요.)")
            }
            else -> {
                item { SectionLabel("${data.fromYear}년 이후 제재공시 ${cases.size}건") }
                items(cases, key = { it.id }) { c -> SanctionCaseCard(c, emptySet(), onOpenLaw) }
            }
        }
        item {
            Text(
                "금감원 '금융회사 제재공시'를 회사명으로 맞춰 보여 줍니다. 원문과 다를 수 있으니 업무에는 금감원 원문을 확인하세요.",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun scopeLabel(f: SanctionFilter, data: SanctionDataset): String = listOfNotNull(
    "기간 ${f.periodLabel(data.fromYear)}",
    f.sector ?: "전 권역",
    f.query.trim().takeIf { it.isNotEmpty() }?.let { "${f.field.label} 검색 ‘$it’" }
).joinToString(" · ")

@Composable
private fun FilterBar(data: SanctionDataset?, f: SanctionFilter, onChange: (SanctionFilter) -> Unit) {
    val fromYear = data?.fromYear ?: SanctionDataset.FIRST_YEAR
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 기간: 빠른 선택 + 직접 지정(시작 연·월 ~ 끝 연·월). 통계와 엑셀 저장도 이 기간을 따른다.
        var pickPeriod by remember { mutableStateOf(false) }
        val preset = f.preset()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SanctionPeriod.entries.forEach { p ->
                PillChip(if (p == SanctionPeriod.ALL) "전체(${fromYear}~)" else p.label, onClick = { onChange(f.withPeriod(p)) }, selected = preset == p)
            }
            PillChip(
                if (preset == null) "기간: ${f.periodLabel(fromYear)}" else "기간 지정…",
                onClick = { pickPeriod = true }, selected = preset == null
            )
        }
        if (pickPeriod && data != null) {
            PeriodDialog(data, f, onDismiss = { pickPeriod = false }) { from, to -> onChange(f.copy(from = from, to = to)); pickPeriod = false }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillChip("전 권역", onClick = { onChange(f.copy(sector = null)) }, selected = f.sector == null)
            SanctionSectors.all.forEach { s -> PillChip(s, onClick = { onChange(f.copy(sector = if (f.sector == s) null else s)) }, selected = f.sector == s) }
        }
        if (f != SanctionFilter()) {
            TextButton(onClick = { onChange(SanctionFilter()) }, modifier = Modifier.padding(start = 10.dp)) { Text("조건 초기화") }
        }
    }
}

/**
 * 공시 한 건(세부내역을 펼친 카드): 회사·권역·날짜·부서, 기관·임원·직원 조치, 지적제목마다 (위반 법률·조항).
 * 조항을 누르면 스마트 법령검색이 열리고, [원문보기]는 금감원 공시 페이지를 연다.
 */
@Composable
private fun SanctionCaseCard(c: SanctionCase, matched: Set<SanctionItem>, onOpenLaw: (String) -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val link = MaterialTheme.colorScheme.primary
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(c.company, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(c.sector, c.date.format(sDate), c.dept).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SmallButton("원문보기") { openExternal(context, c.viewUrl) }
            }
            val actions = buildList {
                SanctionText.line(c.inst).takeIf { it.isNotBlank() }?.let { add("기관" to it) }
                if (c.inst.isBlank() && c.money > 0) add("금전" to listOfNotNull(
                    c.fine.takeIf { it > 0 }?.let { "과태료 ${SanctionStats.won(it)}" },
                    c.penalty.takeIf { it > 0 }?.let { "과징금 ${SanctionStats.won(it)}" }).joinToString(", "))
                SanctionText.line(c.exec).takeIf { it.isNotBlank() }?.let { add("임원" to it) }
                SanctionText.line(c.staff).takeIf { it.isNotBlank() }?.let { add("직원" to it) }
            }
            actions.forEach { (label, text) ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFB4233A), fontWeight = FontWeight.SemiBold)) { append(label) }
                        append("  ")
                        append(text)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (c.items.isEmpty()) {
                Text(c.whyText(), style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                c.items.forEachIndexed { i, it ->
                    val hit = it in matched
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = if (hit) FontWeight.Bold else FontWeight.Medium)) {
                                append("${i + 1}. ")
                                it.category?.let { cat -> append("[$cat] ") }
                                append(it.title)
                            }
                            if (it.laws.isNotEmpty()) {
                                append(" (")
                                it.laws.forEachIndexed { j, law ->
                                    if (j > 0) append(", ")
                                    withLink(LinkAnnotation.Clickable(law, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))) { onOpenLaw(law) }) {
                                        append(law)
                                    }
                                }
                                append(")")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ------------------------------------------------------------------ 통계

@Composable
private fun StatsPane(
    data: SanctionDataset,
    f: SanctionFilter,
    s: SanctionStats.Summary,
    onFilter: (SanctionFilter) -> Unit,
    onOpenLaw: (String) -> Unit,
    onExport: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 범위와 유의사항(디스클레이머)
        Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("${data.fromYear}년 이후 금감원 제재공시 기준", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text("범위: ${scopeLabel(f, data)} · 자료 기준 ${data.generatedAt.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(SanctionExport.NOTICE, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("공시", "%,d건".format(s.caseCount), Modifier.weight(1f))
            Kpi("지적사항", "%,d건".format(s.itemCount), Modifier.weight(1f))
            Kpi("금전제재 합계", SanctionStats.won(s.fine + s.penalty), Modifier.weight(1.3f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("과태료", SanctionStats.won(s.fine), Modifier.weight(1f))
            Kpi("과징금", SanctionStats.won(s.penalty), Modifier.weight(1f))
        }

        StatCard("권역별 공시 건수", hint = "누르면 해당 권역 내역") {
            Bars(s.bySector.map { it.first to it.second }) { onFilter(f.copy(sector = it)) }
        }
        StatCard("기관 조치 종류별") { Bars(s.instKinds.take(10)) {} }
        StatCard("임원 조치 종류별") { Bars(s.execKinds.take(8)) {} }
        StatCard("직원 조치 종류별") { Bars(s.staffKinds.take(8)) {} }
        StatCard("자주 지적된 법률", hint = "누르면 해당 내역") { Bars(s.topLawNames) { onFilter(f.copy(query = it, field = SanctionSearchField.LAW)) } }
        StatCard("자주 지적된 조항", hint = "누르면 내역, 아이콘은 법령검색") {
            Bars(s.topLaws.take(15), trailing = { law ->
                IconButton(onClick = { onOpenLaw(law) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.AccountTree, contentDescription = "$law 법령검색", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }) { onFilter(f.copy(query = it, field = SanctionSearchField.LAW)) }
        }
        StatCard("제재 공시가 많은 회사", hint = "누르면 해당 내역") { Bars(s.topCompanies) { onFilter(f.copy(query = it, field = SanctionSearchField.COMPANY)) } }
        StatCard("금전제재 상위 10건") {
            if (s.topMoney.isEmpty()) Text("해당 없음", style = MaterialTheme.typography.bodySmall)
            s.topMoney.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().clickable { onFilter(f.copy(query = c.company, field = SanctionSearchField.COMPANY).withYear(c.year)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(c.company, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.sector} · ${c.date.format(sDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(SanctionStats.won(c.money), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFB4233A))
                }
            }
        }
        TextButton(onClick = onExport, modifier = Modifier.padding(horizontal = 14.dp)) {
            Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("공시 세부내역·통계 엑셀로 저장")
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatCard(title: String, hint: String? = null, content: @Composable () -> Unit) {
    ListCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        content()
    }
}

@Composable
private fun Bars(values: List<Pair<String, Int>>, trailing: (@Composable (String) -> Unit)? = null, onClick: (String) -> Unit) {
    if (values.isEmpty()) { Text("해당 없음", style = MaterialTheme.typography.bodySmall); return }
    val max = values.maxOf { it.second }.coerceAtLeast(1)
    val bar = AppColors.heroEnd
    values.forEach { (label, v) ->
        Row(Modifier.fillMaxWidth().clickable { onClick(label) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(132.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f).height(12.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(v.toFloat() / max).clip(RoundedCornerShape(6.dp)).background(bar.copy(alpha = 0.85f)))
            }
            Text("%,d".format(v), Modifier.width(44.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing?.invoke(label)
        }
    }
}

/** 기간 직접 지정: 시작 연·월과 끝 연·월을 고른다. */
@Composable
private fun PeriodDialog(data: SanctionDataset, f: SanctionFilter, onDismiss: () -> Unit, onApply: (YearMonth?, YearMonth?) -> Unit) {
    val now = YearMonth.now()
    val years = (data.fromYear..now.year).toList().reversed()
    var fromY by remember { mutableStateOf(f.from?.year ?: data.fromYear) }
    var fromM by remember { mutableStateOf(f.from?.monthValue ?: 1) }
    var toY by remember { mutableStateOf(f.to?.year ?: now.year) }
    var toM by remember { mutableStateOf(f.to?.monthValue ?: now.monthValue) }
    val from = YearMonth.of(fromY, fromM)
    val to = YearMonth.of(toY, toM)
    val valid = !from.isAfter(to)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기간 지정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("조치요구일 기준으로 통계·세부내역·엑셀 저장에 함께 적용됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("시작", Modifier.width(36.dp))
                    NumberDrop(fromY, years, "년") { fromY = it }
                    NumberDrop(fromM, (1..12).toList(), "월") { fromM = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("끝", Modifier.width(36.dp))
                    NumberDrop(toY, years, "년") { toY = it }
                    NumberDrop(toM, (1..12).toList(), "월") { toM = it }
                }
                if (!valid) Text("시작이 끝보다 늦습니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(from, to) }, enabled = valid) { Text("적용") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun NumberDrop(value: Int, options: List<Int>, unit: String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$value$unit", style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text("$o$unit") }, onClick = { onPick(o); open = false }) }
        }
    }
}
