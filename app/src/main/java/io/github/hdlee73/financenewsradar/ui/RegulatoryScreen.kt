package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hdlee73.financenewsradar.data.SummaryEngine
import io.github.hdlee73.financenewsradar.data.SummaryStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.RegulatoryParser
import io.github.hdlee73.financenewsradar.data.RegulatoryRepository
import io.github.hdlee73.financenewsradar.model.RegItem
import io.github.hdlee73.financenewsradar.model.RegSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

data class RegSourceState(
    val items: List<RegItem> = emptyList(),
    val loading: Boolean = false,
    /** 목록은 떴고 상세 내용(검색용)을 받는 중. */
    val loadingBodies: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
    /** 자료 링크 → 기기 내 Gemini Nano가 만든 핵심내용 요약(지원 기기에서만 채워진다). */
    val summaries: Map<String, String> = emptyMap(),
    /** 요약이 아직 없는 자료의 상태 문구(대기·진행·불가 사유). */
    val summaryNotes: Map<String, String> = emptyMap()
)

class RegulatoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RegulatoryRepository(application)
    private val jobs = mutableMapOf<RegSource, Job>()
    private val summaryStore = SummaryStore(application, "summary_cache_regulatory")
    private val summaryJobs = mutableMapOf<RegSource, Job>()
    private val summarySkipped = mutableSetOf<String>()
    private var allSummaries: Map<String, String> = summaryStore.load()
    private val _state = MutableStateFlow(RegSource.entries.associateWith { RegSourceState() })
    val state: StateFlow<Map<RegSource, RegSourceState>> = _state.asStateFlow()

    private fun update(source: RegSource, change: (RegSourceState) -> RegSourceState) {
        _state.update { it + (source to change(it.getValue(source))) }
    }

    /** 화면에 처음 들어올 때: 보관해 둔 목록을 먼저 보여 주고, 아직 읽지 않았으면 새로 읽는다. */
    fun ensure(source: RegSource) {
        val current = _state.value.getValue(source)
        if (current.loaded || current.loading) return
        refresh(source)
    }

    /**
     * Gemini Nano가 있는 기기에서만: 이미 읽어 둔 상세 내용으로 핵심내용 요약을 만들어 제목 아래에 보여 준다.
     * 이미 만든 요약은 다시 만들지 않고, 기기가 지원하지 않으면 아무 것도 하지 않아 화면은 그대로다.
     */
    fun summarize(source: RegSource) {
        if (source == RegSource.FSS_SANCTION) return
        summaryJobs.filterKeys { it != source }.values.forEach { it.cancel() }
        summaryJobs[source]?.cancel()
        summaryJobs[source] = viewModelScope.launch {
            if (!SummaryEngine.isAvailable()) return@launch
            val items = _state.value.getValue(source).items
            // 요약문 저장소는 링크별이라 보관해 둔 요약은 바로 보여 준다.
            val known = items.filter { allSummaries.containsKey(it.link) }.associate { it.link to allSummaries.getValue(it.link) }
            update(source) { it.copy(summaries = it.summaries + known) }
            val todo = items.filter { !allSummaries.containsKey(it.link) && it.link !in summarySkipped && it.body.isNotEmpty() }
            if (todo.isEmpty()) return@launch
            try {
                update(source) { s -> s.copy(summaryNotes = s.summaryNotes + todo.associate { it.link to "요약 대기 중" }) }
                for (item in todo) {
                    update(source) { s -> s.copy(summaryNotes = s.summaryNotes + (item.link to "요약 중…")) }
                    val (kind, focus) = if (source.isGuidance) "행정지도 문서" to "행정지도의 목적과 핵심 내용(대상·조치 사항)"
                    else "유권해석 또는 비조치의견서" to "질의 요지와 회신(결론)"
                    val outcome = SummaryEngine.summarize(item.title, item.body, kind, focus)
                    if (outcome.text != null) {
                        allSummaries = allSummaries + (item.link to outcome.text)
                        summaryStore.save(allSummaries)
                        update(source) { s -> s.copy(summaries = s.summaries + (item.link to outcome.text), summaryNotes = s.summaryNotes - item.link) }
                    } else {
                        summarySkipped += item.link
                        update(source) { s -> s.copy(summaryNotes = s.summaryNotes + (item.link to "요약 불가 · ${outcome.reason}")) }
                    }
                }
            } finally {
                // 중간에 멈췄다면(다른 탭으로 이동 등) 대기·진행 문구는 지운다. 실패 사유는 남긴다.
                update(source) { s -> s.copy(summaryNotes = s.summaryNotes.filterValues { !it.startsWith("요약 대기") && !it.startsWith("요약 중") }) }
            }
        }
    }

    fun refresh(source: RegSource) {
        summarySkipped.clear()
        jobs[source]?.cancel()
        jobs[source] = viewModelScope.launch {
            update(source) { it.copy(loading = true, error = null) }
            if (_state.value.getValue(source).items.isEmpty()) {
                val cached = repository.cached(source)
                if (cached.isNotEmpty()) update(source) { it.copy(items = cached) }
            }
            try {
                val items = repository.load(source) { list ->
                    update(source) { s -> s.copy(items = list.map { n -> s.items.firstOrNull { o -> o.link == n.link && o.body.isNotEmpty() } ?: n }, loadingBodies = true) }
                }
                update(source) { it.copy(items = items, loading = false, loadingBodies = false, loaded = true) }
                summarize(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update(source) { it.copy(loading = false, loadingBodies = false, error = e.message ?: "목록을 읽지 못했습니다.") }
            }
        }
    }
}

/** 서랍 메뉴별 출처 묶음. */
object RegulatoryMenus {
    val guidance = listOf(RegSource.LAW_INTERPRETATION, RegSource.NO_ACTION, RegSource.FSS_GUIDANCE, RegSource.FSC_GUIDANCE)
    val sanction = listOf(RegSource.FSS_SANCTION)
}

private val regDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** 금융회사 주요 현황 아래 메뉴: 유권해석·비조치·행정지도(금융위/금감원)를 최근 20건씩 읽고 제목+내용으로 검색한다. */
@Composable
fun RegulatoryScreen(
    viewModel: RegulatoryViewModel,
    modifier: Modifier = Modifier,
    title: String = "유권해석·비조치의견서·행정지도",
    sources: List<RegSource> = RegulatoryMenus.guidance
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val states by viewModel.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val source = sources[selected.coerceIn(0, sources.lastIndex)]
    val state = states.getValue(source)
    LaunchedEffect(source) { viewModel.ensure(source); if (state.loaded) viewModel.summarize(source) }

    val shown = remember(state.items, query) {
        if (query.isBlank()) state.items else state.items.filter { RegulatoryParser.matches(it, query) }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "title") {
                LargeTitle(title, subtitle = if (source == RegSource.FSS_SANCTION) "금융회사별 제재 현황을 한눈에" else "금융당국 유권해석, 비조치의견 및 행정지도") {
                    IconButton(onClick = { viewModel.refresh(source) }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                }
            }
            if (sources.size > 1) item(key = "tabs") {
                UnderlineTabs(sources.map { it.tabLabel }, selected, { selected = it })
            }
            item(key = "site") {
                Row(
                    Modifier.fillMaxWidth().clickable { openExternal(context, source.listUrl) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${source.label} 사이트에서 전체 목록 보기", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary
                    )
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            item(key = "search") {
                SearchPill(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = if (source == RegSource.FSS_SANCTION) "금융회사명(기관) 검색" else "${source.tabLabel} 제목+내용 검색",
                    onSearch = { keyboard?.hide(); focus.clearFocus() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    description = "${source.tabLabel} 검색어"
                )
            }
            item(key = "label") {
                SectionLabel(
                    if (query.isBlank()) "최근 ${source.count}건 · ${source.label}"
                    else "‘${query.trim()}’ 검색 결과 ${shown.size}건"
                )
            }
            if (state.loadingBodies || (state.loading && state.items.isNotEmpty())) item(key = "bodies") {
                Text(
                    "내용을 받는 중입니다. 받는 대로 제목+내용 검색에 반영됩니다.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                }
                state.error != null && state.items.isEmpty() -> item(key = "error") {
                    CenterMessage(
                        state.error, isError = true, actionLabel = "사이트에서 보기",
                        onAction = { openExternal(context, source.listUrl) }
                    )
                }
                shown.isEmpty() -> item(key = "empty") {
                    CenterMessage(if (query.isBlank()) "표시할 항목이 없습니다." else "일치하는 항목이 없습니다.")
                }
                else -> itemsIndexed(shown, key = { i, it -> "${it.source.name}-$i" }) { _, item ->
                    RegulatoryRow(item, query, state.summaries[item.link], state.summaryNotes[item.link]) { openExternal(context, item.link) }
                }
            }
        }
        ScrollToTopButton(listState)
    }
}

@Composable
private fun RegulatoryRow(item: RegItem, query: String, summary: String?, summaryNote: String?, onOpen: () -> Unit) {
    val date = item.date?.format(regDateFormat)
    val snippet = if (query.isBlank()) null else RegulatoryParser.snippet(item, query)
    // 라벨 없이 값만 보여 준다(제재공시: 요구일 · 관련부서, 행정지도: 소관부서 · 시행일, 유권해석·비조치의견서: 일련번호).
    val meta = when (item.source) {
        RegSource.FSS_SANCTION -> listOfNotNull(date, item.dept)
        RegSource.LAW_INTERPRETATION, RegSource.NO_ACTION -> listOfNotNull(item.serial?.let { "일련번호 $it" })
        else -> listOfNotNull(item.dept, date)
    }
    val stripe = when {
        item.source == RegSource.FSS_SANCTION -> AppColors.heroEnd
        item.inForce == false -> MaterialTheme.colorScheme.outline
        else -> AppColors.heroEnd
    }
    ListCard(stripe = if (item.source == RegSource.FSS_SANCTION) null else stripe, onClick = onOpen) {
    if (item.source == RegSource.FSS_SANCTION) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialAvatar(item.title, stripe)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snippet != null) Text(snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        return@ListCard
    }
        Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // Gemini Nano가 만든 핵심내용 요약(지원 기기에서만 있음). 없으면 이 줄은 나타나지 않는다.
        if (summary != null) {
            Text("✦ $summary", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else if (summaryNote != null) {
            Text("✦ $summaryNote", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.source.isGuidance) {
                when (item.inForce) {
                    true -> TagLabel("시행중")
                    false -> TagLabel(item.statusText?.takeIf { it.isNotBlank() } ?: "종료", accent = true)
                    null -> TagLabel(item.statusText?.takeIf { it.isNotBlank() } ?: "시행여부 -", accent = true)
                }
            }
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (snippet != null) {
            Text(snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
