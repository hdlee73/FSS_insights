package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.ADR_PLACEHOLDER
import io.github.hdlee73.financenewsradar.data.Instrument
import io.github.hdlee73.financenewsradar.data.IntradaySeries
import io.github.hdlee73.financenewsradar.data.KOREA_INDEXES
import io.github.hdlee73.financenewsradar.data.MarketClient
import io.github.hdlee73.financenewsradar.data.PANEL_CATALOG
import io.github.hdlee73.financenewsradar.data.POPULAR_INSTRUMENTS
import io.github.hdlee73.financenewsradar.data.PriceHistory
import io.github.hdlee73.financenewsradar.data.Quote
import io.github.hdlee73.financenewsradar.data.DEFAULT_BRIEFING_STATS
import io.github.hdlee73.financenewsradar.data.DEFAULT_MARKET_STATS
import io.github.hdlee73.financenewsradar.data.STAT_CATALOG
import io.github.hdlee73.financenewsradar.data.StatSelection
import io.github.hdlee73.financenewsradar.data.StatItem
import io.github.hdlee73.financenewsradar.data.StatsApi
import io.github.hdlee73.financenewsradar.data.periodLabel
import io.github.hdlee73.financenewsradar.data.WatchlistStore
import io.github.hdlee73.financenewsradar.data.panelSlots
import io.github.hdlee73.financenewsradar.data.searchLocal
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MarketUiState(
    /** 코스피·코스닥 아래 둘째·셋째 줄 6칸. */
    val panel: List<Instrument> = panelSlots(emptyList()),
    /** 지수 칸 그래프용 당일(장 마감 후엔 직전 거래일) 분봉. */
    val intraday: Map<String, IntradaySeries> = emptyMap(),
    val watch: List<Instrument> = emptyList(),
    val quotes: Map<String, Quote> = emptyMap(),
    val histories: Map<String, PriceHistory> = emptyMap(),
    val isRefreshing: Boolean = false,
    val failed: Boolean = false,
    /** 한국은행 ECOS 기반 국내 금융 통계(서버 /stats). 못 가져온 항목은 없다. */
    val stats: List<StatItem> = emptyList(),
    /** 사용자가 고른 국내 금융 통계(시장동향 탭 / 오늘의 브리핑). */
    val statSelection: StatSelection = StatSelection(DEFAULT_MARKET_STATS.toSet(), DEFAULT_BRIEFING_STATS.toSet())
)

class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val client = MarketClient(application)
    private val store = WatchlistStore(application)
    private val statsApi = StatsApi(application)
    private var statsLoadedAt = 0L
    private val _state = MutableStateFlow(MarketUiState(watch = store.load(), panel = panelSlots(store.loadPanelSlots()), statSelection = store.loadStatSelection()))
    val state: StateFlow<MarketUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private val chartFailedAt = HashMap<String, Long>()
    private val intradayTriedAt = HashMap<String, Long>()

    init {
        // 앞서 설치한 테스트 빌드가 넣어 둔 마이크론 대용 항목도 ADR 자리표시로 바꾼다.
        if (_state.value.watch.any { it.symbol == "MU" && it.name.contains("ADR 대용") }) {
            setWatch(_state.value.watch.map { if (it.symbol == "MU" && it.name.contains("ADR 대용")) it.copy(symbol = ADR_PLACEHOLDER, name = "SK하이닉스 ADR") else it })
        }
        if (_state.value.watch.any { it.symbol == ADR_PLACEHOLDER }) resolveAdr()
    }

    /** SK하이닉스 미국 상장 종목(ADR)을 Yahoo 검색으로 찾아 자리표시 항목을 실제 티커로 바꾼다. */
    private fun resolveAdr() {
        viewModelScope.launch {
            val search = runCatching { client.search("SK hynix") }
            val found = search.getOrNull()
                ?.firstOrNull { !it.isKorean && !it.isIndex && it.name.contains("hynix", ignoreCase = true) }
            val current = _state.value.watch
            if (found != null) {
                val exists = current.any { it.symbol == found.symbol }
                setWatch(current.mapNotNull {
                    if (it.symbol != ADR_PLACEHOLDER) it else if (exists) null else it.copy(symbol = found.symbol, name = "SK하이닉스 ADR")
                })
                refresh()
            } else if (search.isSuccess) {
                // 검색은 됐는데 미국 상장 종목이 없을 때만 목록에서 뺀다(네트워크 오류면 다음 실행에서 다시 시도).
                setWatch(current.filterNot { it.symbol == ADR_PLACEHOLDER })
            }
        }
    }

    /** 지수 패널과 관심종목 시세를 불러온다. 한꺼번에 6개까지만 동시에 요청한다. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val symbols = (KOREA_INDEXES + _state.value.panel + _state.value.watch).map { it.symbol }.distinct()
            var anyFailed = false
            symbols.chunked(6).forEach { chunk ->
                coroutineScope {
                    chunk.map { symbol ->
                        async {
                            runCatching { client.quote(symbol) }
                                .onSuccess { q -> _state.update { it.copy(quotes = it.quotes + (symbol to q)) } }
                                .onFailure { anyFailed = true }
                        }
                    }.awaitAll()
                }
            }
            _state.update { it.copy(isRefreshing = false, failed = anyFailed) }
            loadIntraday()
            loadCharts()
            loadStats()
        }
    }

    /** 국내 금융 통계는 일·월·분기 자료라 30분에 한 번만 받는다. */
    private suspend fun loadStats() {
        if (System.currentTimeMillis() - statsLoadedAt < 1_800_000) return
        statsLoadedAt = System.currentTimeMillis()
        runCatching { statsApi.load(_state.value.statSelection.all) }
            .onSuccess { list -> if (list.isNotEmpty()) _state.update { it.copy(stats = list) } else statsLoadedAt = 0L }
            .onFailure { statsLoadedAt = 0L }
    }

    /** 통계 선택을 저장하고, 새로 고른 통계가 있으면 바로 다시 받아 온다. */
    fun setStatSelection(selection: StatSelection) {
        store.saveStatSelection(selection)
        _state.update { it.copy(statSelection = selection) }
        statsLoadedAt = 0L
        viewModelScope.launch { loadStats() }
    }

    /** 지수 패널만 가볍게 갱신(화면이 열려 있는 동안 10초마다). */
    fun refreshPanel() {
        viewModelScope.launch {
            (KOREA_INDEXES + _state.value.panel).map { it.symbol }.map { symbol ->
                async { runCatching { client.quote(symbol) }.onSuccess { q -> _state.update { it.copy(quotes = it.quotes + (symbol to q)) } } }
            }.awaitAll()
            loadIntraday()
        }
    }

    /** 지수 칸 그래프(당일 분봉)를 1분에 한 번만 다시 받는다. 실패해도 1분 뒤에 다시 시도한다. */
    private suspend fun loadIntraday() {
        val now = System.currentTimeMillis()
        val todo = (KOREA_INDEXES + _state.value.panel).map { it.symbol }.filter { now - (intradayTriedAt[it] ?: 0) > 60_000 }
        todo.forEach { intradayTriedAt[it] = now }
        todo.chunked(4).forEach { chunk ->
            coroutineScope {
                chunk.map { symbol ->
                    async {
                        runCatching { client.intraday(symbol, _state.value.quotes[symbol]?.previous) }
                            .onSuccess { series -> _state.update { it.copy(intraday = it.intraday + (symbol to series)) } }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun loadCharts() {
        val now = System.currentTimeMillis()
        val todo = _state.value.watch.filter {
            it.symbol !in _state.value.histories && now - (chartFailedAt[it.symbol] ?: 0) > 60_000
        }
        todo.chunked(2).forEach { chunk ->
            coroutineScope {
                chunk.map { item ->
                    async {
                        runCatching { client.history(item.symbol) }
                            .onSuccess { h -> _state.update { it.copy(histories = it.histories + (item.symbol to h)) } }
                            .onFailure { chartFailedAt[item.symbol] = System.currentTimeMillis() }
                    }
                }.awaitAll()
            }
        }
    }

    private fun setWatch(list: List<Instrument>) {
        store.save(list)
        _state.update { it.copy(watch = list) }
    }

    fun add(items: List<Instrument>) {
        val existing = _state.value.watch.map { it.symbol }.toSet()
        val fresh = items.filter { it.symbol !in existing }
        if (fresh.isEmpty()) return
        setWatch(_state.value.watch + fresh)
        refresh()
    }

    fun remove(item: Instrument) = setWatch(_state.value.watch.filterNot { it.symbol == item.symbol })

    fun move(item: Instrument, offset: Int) {
        val list = _state.value.watch.toMutableList()
        val from = list.indexOfFirst { it.symbol == item.symbol }
        val to = from + offset
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        setWatch(list)
    }

    /** [index]번째 칸을 [symbol]로 바꾼다. 이미 다른 칸에 있는 지표면 두 칸의 자리를 맞바꾼다. */
    fun setPanelSlot(index: Int, symbol: String) {
        val list = _state.value.panel.toMutableList()
        val pick = PANEL_CATALOG.firstOrNull { it.symbol == symbol } ?: return
        if (index !in list.indices) return
        val other = list.indexOfFirst { it.symbol == symbol }
        if (other == index) return
        if (other >= 0) list[other] = list[index]
        list[index] = pick
        store.savePanelSlots(list.map { it.symbol })
        _state.update { it.copy(panel = list) }
        refresh()
    }

    suspend fun search(query: String): List<Instrument> = client.search(query)
}

private val UpColor = Color(0xFFDC5C60)
private val DownColor = Color(0xFF4B7BC8)
private val ChartColor = Color(0xFF62A893)
private val SoftFill = Color(0xFFF2F5F4)
private val SoftLine = Color(0xFFE6EBE8)

@Composable
internal fun changeColor(change: Double?): Color = when {
    change == null || change == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
    change > 0 -> UpColor
    else -> DownColor
}

private fun grouped(value: Double, digits: Int): String =
    String.format(Locale.KOREA, "%,.${digits}f", value)

/** 상승은 ▲, 하락은 ▼로 표시한다(+/- 부호는 쓰지 않는다). */
private fun signed(value: Double, digits: Int, suffix: String = ""): String =
    (if (value > 0) "▲" else if (value < 0) "▼" else "") + grouped(kotlin.math.abs(value), digits) + suffix

/** 대비 금액과 등락률을 "▼92.30 (▼1.30%)"처럼 등락률을 괄호 안에 묶어 표시한다. */
internal fun deltaText(item: Instrument, quote: Quote): String? =
    quote.change?.let { "${signed(it, changeDigits(item))} (${signed(quote.changePercent ?: 0.0, 2, "%")})" }

internal fun priceText(item: Instrument, value: Double): String = when {
    item.type == Instrument.TYPE_FX -> grouped(value, 2) + if (item.currency == "KRW") "원" else ""
    item.type == Instrument.TYPE_RATE -> grouped(value, 3) + "%"
    item.isIndex -> grouped(value, 2)
    item.currency == "USD" -> "$" + grouped(value, 2)
    else -> grouped(value, 0) + "원"
}

private fun changeDigits(item: Instrument) = if (item.isIndex || item.currency == "USD" || item.type == Instrument.TYPE_FX) 2 else 0

private val StampFormat = DateTimeFormatter.ofPattern("M. d. HH:mm", Locale.KOREA).withZone(ZoneId.of("Asia/Seoul"))

private fun stamp(quote: Quote?): String = when {
    quote == null -> "시세 연결 중"
    quote.timeSeconds == 0L -> "기준시각 없음"
    else -> StampFormat.format(Instant.ofEpochSecond(quote.timeSeconds)) + " 기준" + when {
        quote.market == "OPEN" -> " · 장중 · 10초 갱신"
        quote.market == "CLOSE" -> " · 장마감"
        System.currentTimeMillis() / 1000 - quote.timeSeconds > 300 -> " · 지연/장마감"
        else -> " · 최근 시세"
    }
}

private const val W_NAME = 1.2f
private const val W_PRICE = 1.35f
private const val W_CHART = 1f

/** 시장동향 탭: 지수·환율 패널(코스피·코스닥은 넓은 칸, 나머지는 세 칸씩), 관심종목 표. */
@Composable
fun MarketScreen(viewModel: MarketViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Instrument?>(null) }
    var panelPick by remember { mutableStateOf<Int?>(null) }
    var statPicking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 화면이 보이는 동안만 갱신한다(탭을 벗어나면 중단): 지수 10초, 전체 20초.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        var tick = 0
        while (true) {
            delay(10_000)
            tick++
            if (tick % 2 == 0) viewModel.refresh() else viewModel.refreshPanel()
        }
    }

    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text("시장동향", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp))
                }
                SoftButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "시세 새로고침", Modifier.size(18.dp))
                }
                Spacer(Modifier.width(5.dp))
                SoftButton(onClick = { editing = !editing; panelPick = null }) { Text(if (editing) "완료" else "편집", fontSize = 12.sp) }
                Spacer(Modifier.width(5.dp))
                SoftButton(onClick = { adding = true }) { Text("+ 종목", fontSize = 12.sp) }
            }
        }
        item {
            IndexPanel(state, editing, panelPick) { index -> panelPick = index }
        }
        item { StatsPanel(state.stats, state.statSelection.market) { statPicking = true } }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text("관심종목", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                Text("${state.watch.size}", Modifier.padding(start = 6.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val head = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Text("종목명", Modifier.weight(W_NAME), style = head, color = color)
                Text("현재가 / 대비 · 등락률", Modifier.weight(W_PRICE), style = head, color = color, textAlign = TextAlign.End)
                Text("1년 주가추이", Modifier.weight(W_CHART), style = head, color = color, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
            }
        }
        if (state.watch.isEmpty()) {
            item { CenterMessage("표시할 종목을 추가해 주세요.", Modifier.heightIn(min = 160.dp)) }
        } else {
            items(state.watch, key = { it.symbol }) { item ->
                val index = state.watch.indexOf(item)
                WatchRow(
                    item = item,
                    quote = state.quotes[item.symbol],
                    history = state.histories[item.symbol],
                    editing = editing,
                    canUp = index > 0,
                    canDown = index < state.watch.lastIndex,
                    onUp = { viewModel.move(item, -1) },
                    onDown = { viewModel.move(item, 1) },
                    onRemove = { removing = item }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item {
            Text(
                if (state.failed) "일부 시세를 불러오지 못했습니다. 새로고침으로 다시 시도해 주세요."
                else "공개 시세 기반이라 지연되거나 중단될 수 있습니다. 국내는 NAVER, 해외·환율은 Yahoo Finance.",
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    ScrollToTopButton(listState)
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("관심종목 삭제") },
            text = { Text("${item.name}을(를) 관심종목에서 삭제할까요?") },
            confirmButton = { TextButton(onClick = { viewModel.remove(item); removing = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } }
        )
    }
    if (statPicking) {
        StatPickerDialog(state.statSelection, onDismiss = { statPicking = false }) { viewModel.setStatSelection(it); statPicking = false }
    }
    panelPick?.let { index ->
        PanelPickDialog(
            current = state.panel.getOrNull(index),
            used = state.panel.map { it.symbol }.toSet(),
            onDismiss = { panelPick = null },
            onPick = { viewModel.setPanelSlot(index, it.symbol); panelPick = null }
        )
    }
    if (adding) {
        AddInstrumentDialog(
            owned = state.watch.map { it.symbol }.toSet(),
            search = viewModel::search,
            onDismiss = { adding = false },
            onConfirm = { viewModel.add(it); adding = false }
        )
    }
}

@Composable
private fun SoftButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else SoftFill,
        border = BorderStroke(1.dp, if (isSystemInDarkTheme()) MaterialTheme.colorScheme.outlineVariant else SoftLine)
    ) {
        Box(Modifier.heightIn(min = 36.dp).widthIn(min = 36.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { content() }
    }
}

private val TileTintLight = Color(0xFFEAF0FA)
private val TileTintDark = Color(0xFF1B2433)

/** 첫 줄은 코스피·코스닥(고정), 둘째·셋째 줄은 고른 지표 3칸씩(배경색 다름). 편집 중에는 아래 6칸을 눌러 지표를 바꾼다. */
@Composable
private fun IndexPanel(state: MarketUiState, editing: Boolean, picked: Int?, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KOREA_INDEXES.forEach {
                IndexTile(it, state.quotes[it.symbol], state.intraday[it.symbol], wide = true, tinted = false, editing = false, picked = false, onClick = {}, modifier = Modifier.weight(1f))
            }
        }
        state.panel.chunked(3).forEachIndexed { row, items ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { col, item ->
                    val slot = row * 3 + col
                    IndexTile(item, state.quotes[item.symbol], state.intraday[item.symbol], wide = false, tinted = true, editing = editing, picked = picked == slot, onClick = { onPick(slot) }, modifier = Modifier.weight(1f))
                }
                repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            if (editing) "아래 6칸 중 바꿀 칸을 누르면 표시할 지표를 고를 수 있습니다. 코스피·코스닥은 고정입니다."
            else stamp(state.quotes["^KS11"]) + " · 해외 지수·환율은 지연될 수 있음 · 그래프는 당일(장 마감 후엔 직전 거래일) 흐름",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IndexTile(
    item: Instrument, quote: Quote?, series: IntradaySeries?, wide: Boolean, tinted: Boolean, editing: Boolean, picked: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    val color = changeColor(quote?.change)
    val price = quote?.let { priceText(item, it.price) } ?: "—"
    val delta = quote?.let { deltaText(item, it) } ?: "전일 대비 —"
    val shape = RoundedCornerShape(10.dp)
    val dark = isSystemInDarkTheme()
    val fill = if (tinted) (if (dark) TileTintDark else TileTintLight) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val border = if (picked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else if (editing) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val nameStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val priceStyle = TextStyle(fontSize = if (wide) 18.sp else 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val deltaStyle = TextStyle(fontSize = if (wide) 10.5.sp else 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    Column(
        modifier.background(fill, shape)
            .border(border, shape)
            .clip(shape)
            .clickable(enabled = editing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = nameStyle, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(price, Modifier.padding(top = 2.dp), style = priceStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
                IntradayChart(series, color, Modifier.weight(0.8f).height(30.dp))
            }
            // 등락률 괄호까지 들어가도록 차트 아래 칸 전체 폭을 쓴다(차트 옆 좁은 칸에서는 잘림).
            Text(delta, Modifier.padding(top = 2.dp), style = deltaStyle, color = color, maxLines = 1)
        } else {
            Text(item.name, style = nameStyle, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 코스피·코스닥 이외 칸은 수치를 오른쪽 끝에 맞춘다.
            Text(price, Modifier.fillMaxWidth().padding(top = 1.dp), style = priceStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            Text(delta, Modifier.fillMaxWidth().padding(top = 1.dp), style = deltaStyle, color = color, maxLines = 1, textAlign = TextAlign.End)
            IntradayChart(series, color, Modifier.fillMaxWidth().padding(top = 4.dp).height(26.dp))
        }
    }
}

internal fun statText(item: StatItem, value: Double): String {
    val digits = if (item.isRate) 2 else if (item.unit == "조원") 1 else if (kotlin.math.abs(value) >= 1000) 0 else 1
    return grouped(value, digits) + (if (item.isRate) "%" else if (item.unit.isNotBlank()) " ${item.unit}" else "")
}

internal fun statDelta(item: StatItem): String? {
    val change = item.change ?: return null
    val digits = if (item.isRate) 2 else if (item.unit == "조원") 2 else if (kotlin.math.abs(item.value) >= 1000) 0 else 1
    val percent = if (item.isRate) "" else item.changePercent?.let { " (${signed(it, 2, "%")})" }.orEmpty()
    // 금리는 %p 차이를 그대로 보여 준다.
    return signed(change, digits) + (if (item.isRate) "%p" else "") + percent
}

/** 한국은행 ECOS 국내 금융 통계. 증시 타일과 달리 구분별 목록(이름·기준일 / 값·증감)으로 보여 준다. 보일 통계는 "통계 선택"에서 고른다. */
@Composable
private fun StatsPanel(stats: List<StatItem>, selected: Set<String>, onPick: () -> Unit) {
    val visible = STAT_CATALOG.mapNotNull { def -> stats.firstOrNull { it.id == def.id && def.id in selected } }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("국내 금융 통계", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
            SoftButton(onClick = onPick) { Text("통계 선택", fontSize = 12.sp) }
        }
        val shape = RoundedCornerShape(10.dp)
        if (visible.isEmpty()) {
            Text(
                if (selected.isEmpty()) "표시할 통계가 없습니다. 오른쪽 위 '통계 선택'에서 고르세요." else "통계를 불러오는 중이거나 서버에서 받지 못했습니다.",
                Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape).clip(shape)) {
            visible.groupBy { it.group }.entries
                .sortedBy { e -> STAT_CATALOG.indexOfFirst { it.group == e.key }.let { if (it < 0) Int.MAX_VALUE else it } }
                .forEach { (group, rows) ->
                    Text(
                        group,
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    rows.forEachIndexed { index, item ->
                        val color = changeColor(item.change)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(periodLabel(item.period) + " 기준", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(statText(item, item.value), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp), maxLines = 1)
                                Text(statDelta(item) ?: "직전 값 —", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
                            }
                        }
                        if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
        }
        Text(
            "한국은행 ECOS 기준. 기준금리는 직전 변경 대비, 나머지는 직전 관측값 대비.",
            Modifier.padding(start = 2.dp, top = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 국내 금융 통계 선택: 후보마다 "시장동향" / "브리핑" 체크로 어디에 보일지 고른다. */
@Composable
private fun StatPickerDialog(current: StatSelection, onDismiss: () -> Unit, onSave: (StatSelection) -> Unit) {
    var market by remember { mutableStateOf(current.market) }
    var briefing by remember { mutableStateOf(current.briefing) }
    fun toggle(set: Set<String>, id: String) = if (id in set) set - id else set + id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("표시할 통계 선택") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text("시장동향", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        Text("브리핑", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
                STAT_CATALOG.groupBy { it.group }.forEach { (group, defs) ->
                    item(key = "g-$group") {
                        Text(group, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    items(defs, key = { it.id }) { def ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(def.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                Checkbox(def.id in market, { market = toggle(market, def.id) })
                            }
                            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                Checkbox(def.id in briefing, { briefing = toggle(briefing, def.id) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(StatSelection(market, briefing)) }) { Text("저장") } },
        dismissButton = {
            Row {
                TextButton(onClick = { market = DEFAULT_MARKET_STATS.toSet(); briefing = DEFAULT_BRIEFING_STATS.toSet() }) { Text("기본값") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

/** 전일 종가 기준선(점선)과 당일 흐름선, 기준선과 흐름선 사이 옅은 면, 마지막 점. */
@Composable
internal fun IntradayChart(series: IntradaySeries?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val data = series ?: return@Canvas
        val pts = data.points
        if (pts.size < 2) return@Canvas
        val base = data.previous?.takeIf { it > 0 }
        var lo = pts.min()
        var hi = pts.max()
        if (base != null) { lo = minOf(lo, base); hi = maxOf(hi, base) }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 3.dp.toPx()
        fun yOf(v: Double) = pad + (size.height - 2 * pad) * (1f - ((v - lo) / span).toFloat())
        fun xOf(i: Int) = i / (pts.size - 1f) * (size.width - pad)
        val line = Path()
        pts.forEachIndexed { i, v -> if (i == 0) line.moveTo(xOf(i), yOf(v)) else line.lineTo(xOf(i), yOf(v)) }
        val baseY = base?.let { yOf(it) } ?: size.height
        val area = Path().apply {
            addPath(line)
            lineTo(xOf(pts.lastIndex), baseY)
            lineTo(xOf(0), baseY)
            close()
        }
        drawPath(area, color.copy(alpha = 0.16f))
        if (base != null) {
            drawLine(
                color.copy(alpha = 0.55f), Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )
        }
        drawPath(line, color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(xOf(pts.lastIndex), yOf(pts.last())))
    }
}

/** 둘째·셋째 줄 칸에 보여 줄 지표 고르기. 이미 다른 칸에 있는 지표를 고르면 두 칸의 자리가 바뀝니다. */
@Composable
private fun PanelPickDialog(current: Instrument?, used: Set<String>, onDismiss: () -> Unit, onPick: (Instrument) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("표시할 지표 선택") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(PANEL_CATALOG, key = { it.symbol }) { item ->
                    val isCurrent = item.symbol == current?.symbol
                    Row(Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = isCurrent, onClick = null)
                        Column(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            val note = when {
                                isCurrent -> "현재 칸"
                                item.symbol in used -> "다른 칸에 표시 중 · 자리 바꿈"
                                else -> ""
                            }
                            Text(
                                listOf(item.symbol.removePrefix("^"), note).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun WatchRow(
    item: Instrument,
    quote: Quote?,
    history: PriceHistory?,
    editing: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    val color = changeColor(quote?.change)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(W_NAME)) {
                Text(item.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (when (item.type) { Instrument.TYPE_ETF -> "ETF "; Instrument.TYPE_INDEX -> "지수 "; else -> "주식 " }) +
                        item.symbol.replace(Regex("\\.(KS|KQ)$"), "").removePrefix("^"),
                    Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Column(Modifier.weight(W_PRICE), horizontalAlignment = Alignment.End) {
                Text(quote?.let { priceText(item, it.price) } ?: "—", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp), color = color, maxLines = 1)
                Text(
                    quote?.let { deltaText(item, it) } ?: "—",
                    Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = color, maxLines = 1
                )
            }
            Column(Modifier.weight(W_CHART), horizontalAlignment = Alignment.End) {
                if (history == null) {
                    Text("차트 불러오는 중…", style = TextStyle(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                } else {
                    Sparkline(history.closes, Modifier.fillMaxWidth().height(30.dp))
                    Text("고 ${compactPrice(item, history.high)}", Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 9.sp), color = UpColor, maxLines = 1, softWrap = false)
                    Text("저 ${compactPrice(item, history.low)}", style = TextStyle(fontSize = 9.sp), color = DownColor, maxLines = 1, softWrap = false)
                }
            }
        }
        if (editing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ArrowUpward, contentDescription = "위로") }
                IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ArrowDownward, contentDescription = "아래로") }
                IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun compactPrice(item: Instrument, value: Double): String = when {
    item.isIndex -> grouped(value, 2)
    item.currency == "USD" -> "$" + grouped(value, 2)
    else -> grouped(value, 0) + "원"
}

@Composable
private fun Sparkline(closes: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (closes.size < 2) return@Canvas
        val lo = closes.min()
        val span = (closes.max() - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        closes.forEachIndexed { i, v ->
            val x = i / (closes.size - 1f) * size.width
            val y = size.height - ((v - lo) / span).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ChartColor, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 관심종목 추가: 이름·코드 검색 + 한국/미국 필터, 여러 개를 체크해 한꺼번에 추가. */
@Composable
private fun AddInstrumentDialog(
    owned: Set<String>,
    search: suspend (String) -> List<Instrument>,
    onDismiss: () -> Unit,
    onConfirm: (List<Instrument>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("ALL") }
    var results by remember { mutableStateOf(POPULAR_INSTRUMENTS) }
    var status by remember { mutableStateOf("자주 찾는 종목") }
    var searching by remember { mutableStateOf(false) }
    var submit by remember { mutableStateOf(0) }
    val selected = remember { mutableStateOf(listOf<Instrument>()) }

    LaunchedEffect(submit) {
        val q = query.trim()
        if (submit == 0 || q.isEmpty()) return@LaunchedEffect
        searching = true
        status = "검색 중…"
        results = searchLocal(q)
        runCatching { search(q) }
            .onSuccess { results = it; status = if (it.isEmpty()) "검색 결과가 없습니다" else "검색 결과 ${it.size}건" }
            .onFailure { status = it.message ?: "검색하지 못했습니다" }
        searching = false
    }

    val shown = results.filter { market == "ALL" || it.isKorean == (market == "KR") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("관심종목 선택") },
        text = {
            Column {
                SearchPill(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "주식·ETF·지수 이름 또는 코드",
                    onSearch = { submit++ },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "전체", "KR" to "한국", "US" to "미국").forEach { (key, label) ->
                        PillChip(label, selected = market == key, onClick = { market = key })
                    }
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.symbol }) { item ->
                        val already = item.symbol in owned
                        val checked = already || selected.value.any { it.symbol == item.symbol }
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !already) {
                                selected.value = if (checked) selected.value.filterNot { it.symbol == item.symbol } else selected.value + item
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !already)
                            Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.symbol} · ${if (item.isKorean) "한국" else "미국"} · " +
                                        (when (item.type) { Instrument.TYPE_ETF -> "ETF"; Instrument.TYPE_INDEX -> "지수"; else -> "주식" }) +
                                        if (already) " · 추가됨" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.value) }, enabled = selected.value.isNotEmpty() && !searching) {
                Text(if (selected.value.isEmpty()) "선택 종목 추가" else "${selected.value.size}개 추가")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}
