package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.hdlee73.financenewsradar.data.Instrument
import io.github.hdlee73.financenewsradar.data.MARKET_PANEL
import io.github.hdlee73.financenewsradar.data.MarketClient
import io.github.hdlee73.financenewsradar.data.POPULAR_INSTRUMENTS
import io.github.hdlee73.financenewsradar.data.PriceHistory
import io.github.hdlee73.financenewsradar.data.Quote
import io.github.hdlee73.financenewsradar.data.WatchlistStore
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
    val watch: List<Instrument> = emptyList(),
    val quotes: Map<String, Quote> = emptyMap(),
    val histories: Map<String, PriceHistory> = emptyMap(),
    val isRefreshing: Boolean = false,
    val failed: Boolean = false
)

class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val client = MarketClient(application)
    private val store = WatchlistStore(application)
    private val _state = MutableStateFlow(MarketUiState(watch = store.load()))
    val state: StateFlow<MarketUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private val chartFailedAt = HashMap<String, Long>()

    /** 지수 패널과 관심종목 시세를 불러온다. 한꺼번에 6개까지만 동시에 요청한다. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val symbols = (MARKET_PANEL + _state.value.watch).map { it.symbol }.distinct()
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
            loadCharts()
        }
    }

    /** 지수 패널만 가볍게 갱신(화면이 열려 있는 동안 10초마다). */
    fun refreshPanel() {
        viewModelScope.launch {
            MARKET_PANEL.map { it.symbol }.map { symbol ->
                async { runCatching { client.quote(symbol) }.onSuccess { q -> _state.update { it.copy(quotes = it.quotes + (symbol to q)) } } }
            }.awaitAll()
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

    suspend fun search(query: String): List<Instrument> = client.search(query)
}

private val UpColor = Color(0xFFD83A34)
private val DownColor = Color(0xFF2F6FDE)

@Composable
private fun changeColor(change: Double?): Color = when {
    change == null || change == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
    change > 0 -> UpColor
    else -> DownColor
}

private fun grouped(value: Double, digits: Int): String =
    String.format(Locale.KOREA, "%,.${digits}f", value)

private fun signed(value: Double, digits: Int, suffix: String = ""): String =
    (if (value > 0) "+" else if (value < 0) "-" else "") + grouped(kotlin.math.abs(value), digits) + suffix

private fun priceText(item: Instrument, value: Double): String = when {
    item.type == Instrument.TYPE_FX -> grouped(value, 2) + "원"
    item.isIndex -> grouped(value, 2)
    item.currency == "USD" -> "$" + grouped(value, 2)
    else -> grouped(value, 0) + "원"
}

private fun changeDigits(item: Instrument) = if (item.isIndex || item.currency == "USD" || item.type == Instrument.TYPE_FX) 2 else 0

private val StampFormat = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREA).withZone(ZoneId.of("Asia/Seoul"))

private fun stamp(quote: Quote?): String = when {
    quote == null -> "시세 연결 중"
    quote.timeSeconds == 0L -> "기준시각 없음"
    else -> StampFormat.format(Instant.ofEpochSecond(quote.timeSeconds)) + " 기준" + when {
        quote.market == "OPEN" -> " · 장중"
        quote.market == "CLOSE" -> " · 장마감"
        System.currentTimeMillis() / 1000 - quote.timeSeconds > 300 -> " · 지연/장마감"
        else -> " · 최근 시세"
    }
}

/** 증시 동향 탭: 시장 지수·환율 패널과 로컬에 저장되는 관심종목(현재가·등락·1년 차트). */
@Composable
fun MarketScreen(viewModel: MarketViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Instrument?>(null) }

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

    Column(modifier.fillMaxSize()) {
        LargeTitle("증시 동향") {
            IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { IndexPanel(state) }
            item { SectionBand() }
            item {
                BlockHeader("관심종목 ${state.watch.size}", accent = true) {
                    if (state.watch.size > 1) TextButton(onClick = { editing = !editing }) {
                        Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, contentDescription = null, modifier = Modifier.height(16.dp))
                        Text(if (editing) "완료" else "편집")
                    }
                    TextButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                        Text("종목")
                    }
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
                    RowDivider()
                }
            }
            item {
                Text(
                    if (state.failed) "일부 시세를 불러오지 못했습니다. 새로고침으로 다시 시도해 주세요."
                    else "공개 시세 기반이라 지연되거나 중단될 수 있습니다. 국내는 NAVER, 해외·환율은 Yahoo Finance.",
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
private fun IndexPanel(state: MarketUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MARKET_PANEL.take(2).forEach { IndexTile(it, state.quotes[it.symbol], Modifier.weight(1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MARKET_PANEL.drop(2).forEach { IndexTile(it, state.quotes[it.symbol], Modifier.weight(1f)) }
        }
        Text(
            stamp(state.quotes["^KS11"]) + " · 해외 지수·환율은 지연될 수 있음",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IndexTile(item: Instrument, quote: Quote?, modifier: Modifier = Modifier) {
    val color = changeColor(quote?.change)
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(item.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            quote?.let { priceText(item, it.price) } ?: "—",
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            quote?.change?.let { "${signed(it, 2)} (${signed(quote.changePercent ?: 0.0, 2, "%")})" } ?: "전일 대비 —",
            style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
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
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = if (editing) 4.dp else 18.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.15f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (when (item.type) { Instrument.TYPE_ETF -> "ETF "; Instrument.TYPE_INDEX -> "지수 "; else -> "주식 " }) +
                    item.symbol.replace(Regex("\\.(KS|KQ)$"), "").removePrefix("^"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.End) {
            Text(quote?.let { priceText(item, it.price) } ?: "—", style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
            Text(
                quote?.change?.let { signed(it, changeDigits(item)) } ?: "—",
                style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1
            )
            Text(
                quote?.changePercent?.let { "(${signed(it, 2, "%")})" } ?: "",
                style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1
            )
        }
        Column(Modifier.weight(0.9f), horizontalAlignment = Alignment.End) {
            if (history == null) {
                Text("차트 불러오는 중…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            } else {
                Sparkline(history.closes, Modifier.fillMaxWidth().height(32.dp))
                Text("고 ${compactPrice(item, history.high)}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = UpColor, maxLines = 1)
                Text("저 ${compactPrice(item, history.low)}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = DownColor, maxLines = 1)
            }
        }
        if (editing) {
            Column {
                Row {
                    IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.width(36.dp).height(36.dp)) { Icon(Icons.Default.ArrowUpward, contentDescription = "위로") }
                    IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.width(36.dp).height(36.dp)) { Icon(Icons.Default.ArrowDownward, contentDescription = "아래로") }
                }
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.End).width(36.dp).height(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun compactPrice(item: Instrument, value: Double): String = when {
    item.isIndex -> grouped(value, 2)
    item.currency == "USD" -> "$" + grouped(value, 2)
    else -> grouped(value, 0)
}

@Composable
private fun Sparkline(closes: List<Double>, modifier: Modifier = Modifier) {
    val color = if (closes.size > 1 && closes.last() >= closes.first()) UpColor else DownColor
    Canvas(modifier) {
        if (closes.size < 2) return@Canvas
        val lo = closes.min()
        val span = (closes.max() - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        closes.forEachIndexed { i, v ->
            val x = i / (closes.size - 1f) * size.width
            val y = (size.height - ((v - lo) / span).toFloat() * size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
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
