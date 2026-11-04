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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.SanctionAction
import io.github.hdlee73.financenewsradar.data.SanctionCase
import io.github.hdlee73.financenewsradar.data.SanctionDataset
import io.github.hdlee73.financenewsradar.data.SanctionExport
import io.github.hdlee73.financenewsradar.data.SanctionFilter
import io.github.hdlee73.financenewsradar.data.SanctionPeriod
import io.github.hdlee73.financenewsradar.data.SanctionRepository
import io.github.hdlee73.financenewsradar.data.SanctionRow
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
 * 서랍 메뉴 "금융회사 제재공시": 첫 탭은 기존 '최근 공시' 화면 그대로(최근 50건),
 * 둘째 탭은 2016년 이후 제재 세부내역(지적사항 단위)과 통계.
 * [onOpenLaw]는 "보험업법 제95조의2" 같은 조문을 스마트 법령검색에서 바로 연다.
 */
@Composable
fun SanctionScreen(
    regulatoryViewModel: RegulatoryViewModel,
    sanctionViewModel: SanctionViewModel,
    onOpenLaw: (String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs: @Composable () -> Unit = { UnderlineTabs(listOf("최근 공시", "세부내역·통계"), tab, { tab = it }) }
    if (tab == 0) {
        RegulatoryScreen(regulatoryViewModel, title = "금융회사 제재공시", sources = RegulatoryMenus.sanction, headerTabs = tabs)
    } else {
        BackHandler { tab = 0 }
        SanctionDetailPane(sanctionViewModel, tabs, onOpenLaw)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SanctionDetailPane(vm: SanctionViewModel, tabs: @Composable () -> Unit, onOpenLaw: (String) -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensure() }
    var openCase by rememberSaveable { mutableStateOf<String?>(null) }
    val data = state.data
    val filter = state.filter

    val rows = remember(data, filter) { if (data == null) emptyList() else SanctionStats.filter(data, filter) }
    val summary = remember(rows, data) { SanctionStats.summarize(rows, data?.years.orEmpty(), filter) }

    val selected = openCase?.let { id -> data?.cases?.firstOrNull { it.id == id } }
    if (selected != null) {
        BackHandler { openCase = null }
        SanctionCaseScreen(selected, onBack = { openCase = null }, onOpenLaw = onOpenLaw)
        return
    }

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
        saver.launch("제재공시_${period}_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.xlsx")
    }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "title") {
                LargeTitle("금융회사 제재공시", subtitle = "2016년 이후 제재 세부내역과 통계") {
                    IconButton(onClick = ::export, enabled = data != null) { Icon(Icons.Default.Download, contentDescription = "엑셀로 저장") }
                    IconButton(onClick = { vm.load(force = true) }, enabled = !state.loading) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
                }
            }
            item(key = "tabs") { tabs() }
            item(key = "mode") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("세부내역", onClick = { vm.showStats(false) }, selected = !state.stats)
                    PillChip("통계", onClick = { vm.showStats(true) }, selected = state.stats)
                }
            }
            item(key = "search") {
                SearchPill(
                    value = filter.query,
                    onValueChange = { vm.setFilter(filter.copy(query = it)) },
                    placeholder = "회사명·지적제목·법조문 검색",
                    onSearch = { keyboard?.hide(); focus.clearFocus() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    description = "제재 세부내역 검색어"
                )
            }
            item(key = "filters") { FilterBar(data, filter) { vm.setFilter(it) } }
            when {
                data == null && state.loading -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text("2016년 이후 제재 세부내역을 받는 중입니다…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        SectionLabel("지적 ${"%,d".format(rows.size)}건 · 공시 ${"%,d".format(summary.caseCount)}건 · 2016년 이후") {
                            TextButton(onClick = ::export) {
                                Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("엑셀 저장")
                            }
                        }
                    }
                    if (rows.isEmpty()) item(key = "empty") { CenterMessage("조건에 맞는 내역이 없습니다.") }
                    items(rows, key = { "${it.case.id}#${it.index}" }) { row ->
                        SanctionRowCard(row, onOpen = { openCase = row.case.id }, onOpenLaw = onOpenLaw)
                    }
                }
            }
        }
        ScrollToTopButton(listState)
    }
}

private fun scopeLabel(f: SanctionFilter, data: SanctionDataset): String = listOfNotNull(
    "기간 ${f.periodLabel(data.fromYear)}",
    f.sector ?: "전 권역",
    f.action.takeIf { it != SanctionAction.ALL }?.label,
    f.query.trim().takeIf { it.isNotEmpty() }?.let { "검색어 ‘$it’" }
).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(data: SanctionDataset?, f: SanctionFilter, onChange: (SanctionFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 기간: 빠른 선택 + 직접 지정(시작 연·월 ~ 끝 연·월). 통계와 엑셀 저장도 이 기간을 따른다.
        var pickPeriod by remember { mutableStateOf(false) }
        val preset = f.preset()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SanctionPeriod.entries.forEach { p ->
                PillChip(if (p == SanctionPeriod.ALL) "전체(${data?.fromYear ?: 2016}~)" else p.label, onClick = { onChange(f.withPeriod(p)) }, selected = preset == p)
            }
            PillChip(
                if (preset == null) "기간: ${f.periodLabel(data?.fromYear ?: 2016)}" else "기간 지정…",
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
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var open by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { open = true },
                    shape = RoundedCornerShape(50),
                    color = if (f.action != SanctionAction.ALL) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("조치수준: ${f.action.label}", style = MaterialTheme.typography.labelLarge, color = if (f.action != SanctionAction.ALL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SanctionAction.entries.forEach { a ->
                        DropdownMenuItem(text = { Text(a.label) }, onClick = { onChange(f.copy(action = a)); open = false })
                    }
                }
            }
            if (f != SanctionFilter()) TextButton(onClick = { onChange(SanctionFilter()) }) { Text("조건 초기화") }
        }
    }
}

/** 조문 칩: 누르면 스마트 법령검색이 그 조문으로 열린다. */
@Composable
private fun LawChip(law: String, onOpenLaw: (String) -> Unit) {
    Surface(
        onClick = { onOpenLaw(law) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.AccountTree, contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            Text(law, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline, maxLines = 1)
        }
    }
}

@Composable
private fun ActionTag(text: String, strong: Boolean) {
    val bg = if (strong) Color(0xFFFDE8EA) else Color(0xFFFFF1DC)
    val fg = if (strong) Color(0xFFB4233A) else Color(0xFF9A5A00)
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 공시 한 건의 조치를 짧은 태그로(금전제재·기관·임원·직원). */
private fun actionTags(c: SanctionCase): List<Pair<String, Boolean>> {
    val tags = mutableListOf<Pair<String, Boolean>>()
    if (c.fine > 0) tags += "과태료 ${SanctionStats.won(c.fine)}" to true
    if (c.penalty > 0) tags += "과징금 ${SanctionStats.won(c.penalty)}" to true
    c.instKinds.filter { it != "과태료" && it != "과징금" || (it == "과태료" && c.fine == 0L) || (it == "과징금" && c.penalty == 0L) }
        .forEach { tags += "기관 $it" to (it != "주의" && it != "개선" && it != "경영유의") }
    if (c.execKinds.isNotEmpty()) tags += "임원 ${c.execKinds.take(2).joinToString("·")}" to false
    if (c.staffKinds.isNotEmpty()) tags += "직원 ${c.staffKinds.take(2).joinToString("·")}" to false
    return tags
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SanctionRowCard(row: SanctionRow, onOpen: () -> Unit, onOpenLaw: (String) -> Unit) {
    val c = row.case
    ListCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.item?.category?.let { TagLabel(it, accent = it == "문책") }
            Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (row.item == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        Text("${c.company} · ${c.sector} · ${c.date.format(sDate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val tags = actionTags(c)
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.take(5).forEach { (t, strong) -> ActionTag(t, strong) }
        }
        if (row.laws.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.laws.take(4).forEach { LawChip(it, onOpenLaw) }
            if (row.laws.size > 4) Text("외 ${row.laws.size - 4}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 공시 한 건 상세: 조치수준(신분·금전)과 지적사항별 위반 조문. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SanctionCaseScreen(c: SanctionCase, onBack: () -> Unit, onOpenLaw: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        BackHeader(c.company, onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoLine("권역", c.sector)
                    InfoLine("조치요구일", c.date.format(sDate))
                    InfoLine("담당부서", c.dept)
                }
            }
            item { SectionLabel("조치수준") }
            item {
                ListCard {
                    if (c.fine > 0 || c.penalty > 0) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (c.fine > 0) ActionTag("과태료 ${SanctionStats.won(c.fine)}", true)
                            if (c.penalty > 0) ActionTag("과징금 ${SanctionStats.won(c.penalty)}", true)
                        }
                    }
                    InfoLine("기관", c.inst.ifBlank { "-" })
                    InfoLine("임원", c.exec.ifBlank { "-" })
                    InfoLine("직원", c.staff.ifBlank { "-" })
                }
            }
            item { SectionLabel(if (c.items.isEmpty()) "지적사항 (원문 참조)" else "지적사항 ${c.items.size}건") }
            if (c.items.isEmpty()) item {
                CenterMessage("이 공시는 지적사항을 자동으로 나누지 못했습니다. 아래 원문을 확인해 주세요.")
            }
            items(c.items.size) { i ->
                val it = c.items[i]
                ListCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        it.category?.let { cat -> TagLabel(cat, accent = cat == "문책") }
                        Text("${i + 1}. ${it.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (it.laws.isEmpty()) Text("위반 조문을 찾지 못했습니다(원문 참조).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { it.laws.forEach { l -> LawChip(l, onOpenLaw) } }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    c.fileUrl?.let { url ->
                        TextButton(onClick = { openExternal(context, url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("원문 문서 열기")
                        }
                    }
                    TextButton(onClick = { openExternal(context, c.viewUrl) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("금감원 공시 페이지")
                    }
                    Text("자동으로 읽어 정리한 내용이라 원문과 다를 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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

        if (s.byMonth.size in 2..24) {
            StatCard("월별 공시 건수 (${f.periodLabel(data.fromYear)})") { MonthBars(s.byMonth) }
        }
        StatCard("연도별 공시 건수", hint = "누르면 그 해로 기간 변경") {
            val selectedYear = f.from?.takeIf { f.to != null && it.year == f.to.year && it.monthValue == 1 && f.to.monthValue == 12 }?.year
            YearBars(s.byYear, selectedYear) { y -> onFilter(if (selectedYear == y) f.copy(from = null, to = null) else f.withYear(y)) }
        }
        StatCard("권역별 공시 건수", hint = "누르면 해당 권역 내역") {
            Bars(s.bySector.map { it.first to it.second }) { onFilter(f.copy(sector = it)) }
        }
        StatCard("기관 조치 종류별") { Bars(s.instKinds.take(10)) {} }
        StatCard("임원 조치 종류별") { Bars(s.execKinds.take(8)) {} }
        StatCard("직원 조치 종류별") { Bars(s.staffKinds.take(8)) {} }
        StatCard("자주 지적된 법률", hint = "누르면 해당 내역") { Bars(s.topLawNames) { onFilter(f.copy(query = it)) } }
        StatCard("자주 지적된 조항", hint = "누르면 내역, 아이콘은 법령검색") {
            Bars(s.topLaws.take(15), trailing = { law ->
                IconButton(onClick = { onOpenLaw(law) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.AccountTree, contentDescription = "$law 법령검색", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }) { onFilter(f.copy(query = it)) }
        }
        StatCard("제재 공시가 많은 회사", hint = "누르면 해당 내역") { Bars(s.topCompanies) { onFilter(f.copy(query = it)) } }
        StatCard("금전제재 상위 10건") {
            if (s.topMoney.isEmpty()) Text("해당 없음", style = MaterialTheme.typography.bodySmall)
            s.topMoney.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().clickable { onFilter(f.copy(query = c.company).withYear(c.year)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("세부내역·통계 엑셀로 저장")
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

@Composable
private fun YearBars(values: List<Pair<Int, Int>>, selected: Int?, onClick: (Int) -> Unit) {
    if (values.isEmpty()) return
    val max = values.maxOf { it.second }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { (y, v) ->
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onClick(y) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text("$v", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max * 0.78f).coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (selected == null || selected == y) AppColors.heroEnd else AppColors.heroEnd.copy(alpha = 0.3f))
                )
                Text("'${y % 100}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MonthBars(values: List<Pair<YearMonth, Int>>) {
    val max = values.maxOf { it.second }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, (m, v) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (values.size <= 12) Text("$v", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max * 0.78f).coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(AppColors.heroEnd.copy(alpha = 0.85f))
                )
                val label = if (m.monthValue == 1 || i == 0) "'${m.year % 100}.${m.monthValue}" else if (values.size <= 12 || m.monthValue % 3 == 1) "${m.monthValue}" else ""
                Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
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
