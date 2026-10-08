package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.CalendarApi
import io.github.hdlee73.financenewsradar.data.CalendarEvent
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val events: List<CalendarEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val api = CalendarApi()
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()
    val isConfigured: Boolean get() = api.isConfigured

    init { if (api.isConfigured) refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { api.load() }
                .onSuccess { list -> _state.update { it.copy(events = list, isLoading = false) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "일정을 불러오지 못했습니다.") } }
        }
    }
}

/** 일정 탭: 월간 달력에서 날짜를 눌러 그날의 금융 일정(금통위·해외 통화정책·지표 등)을 본다. 보기 전용이며 알림은 없다. */
@Composable
fun CalendarScreen(viewModel: CalendarViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)

    Column(modifier.fillMaxSize()) {
        LargeTitle("금융 일정") {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
        if (!viewModel.isConfigured) {
            CenterMessage("서버가 아직 연결되지 않았습니다.\n연결 설정 후 일정을 볼 수 있습니다.")
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthText = month.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 달") }
            Text(
                "${month.year}년 ${month.monthValue}월",
                Modifier.weight(1f).clickable { monthText = YearMonth.from(today).toString(); selectedText = today.toString() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            IconButton(onClick = { monthText = month.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달") }
        }
        MonthGrid(month, today, selected, state.events) { selectedText = it.toString() }
        SectionBand()
        val dayEvents = state.events.filter { it.covers(selected) }
        BlockHeader("${selected.monthValue}월 ${selected.dayOfMonth}일 (${koreanDay(selected.dayOfWeek)})")
        when {
            state.isLoading && state.events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            state.error != null && state.events.isEmpty() -> CenterMessage(state.error.orEmpty(), isError = true, actionLabel = "다시 시도", onAction = viewModel::refresh)
            dayEvents.isEmpty() -> Text("이 날의 일정이 없습니다.", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(dayEvents) { EventRow(it) ; RowDivider() }
            }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, selected: LocalDate, events: List<CalendarEvent>, onSelect: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY).forEach {
                Text(koreanDay(it), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        monthCells(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isSelected = day == selected
                            val has = events.any { it.covers(day) }
                            Column(
                                Modifier.size(width = 40.dp, height = 40.dp).clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { onSelect(day) },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "${day.dayOfMonth}", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (day == today) FontWeight.ExtraBold else FontWeight.Normal,
                                    color = if (day == today) AppColors.accent else MaterialTheme.colorScheme.onSurface
                                )
                                Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (has) AppColors.accent else Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = event.url.startsWith("http")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(event.url))) }
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TagLabel(event.category)
            if (!event.confirmed) TagLabel("일정 확인 필요", accent = true)
        }
        Text(event.title, style = MaterialTheme.typography.titleSmall)
        if (event.endDate != event.date) Text("${event.date} ~ ${event.endDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 일요일 시작 달력의 칸: 앞쪽은 빈 칸(null)으로 채우고 주 단위(7칸)로 맞춘다. */
internal fun monthCells(month: YearMonth): List<LocalDate?> {
    val lead = month.atDay(1).dayOfWeek.value % 7
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    return cells + List((7 - cells.size % 7) % 7) { null }
}

private fun koreanDay(d: DayOfWeek): String = "월화수목금토일"[d.value - 1].toString()
