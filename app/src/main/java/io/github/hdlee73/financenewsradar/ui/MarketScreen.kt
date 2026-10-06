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
import io.github.hdlee73.financenewsradar.data.ADR_PLACEHOLDER
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

private val UpColor = Color(0xFFDC5C60)
private val DownColor = Color(0xFF4B7BC8)
private val ChartColor = Color(0xFF62A893)
private val SoftFill = Color(0xFFF2F5F4)
private val SoftLine = Color(0xFFE6EBE8)

@Composable
private fun changeColor(change: Double?): Color = when {
    change == null || change == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
    change > 0 -> UpColor
    else -> DownColor
}

private fun grouped(value: Double, digits: Int): String =
    String.format(Locale.KOREA, "%,.${digits}f", value)

/** 상승은 +, 하락은 ▼로 표시한다(InvestOn과 동일). */
private fun signed(value: Double, digits: Int, suffix: String = ""): String =
    (if (value > 0) "+" else if (value < 0) "▼" else "") + grouped(kotlin.math.abs(value), digits) + suffix

private fun priceText(item: Instrument, value: Double): String = when {
    item.type == Instrument.TYPE_FX -> grouped(value, 2) + "원"
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

private const val W_NAME = 1.45f
private const val W_PRICE = 1f
private const val W_RATE = 0.8f
private const val W_CHART = 0.85f

/** 증시 동향 탭: InvestOn 첫 화면과 같은 구성(관심종목 제목, 지수·환율 패널, 종목 표). */
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

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text("관심종목", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp))
                    Text("${state.watch.size}", Modifier.padding(start = 6.dp, bottom = 3.dp), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SoftButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "시세 새로고침", Modifier.size(18.dp))
                }
                if (state.watch.size > 1) {
                    Spacer(Modifier.width(5.dp))
                    SoftButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집", fontSize = 12.sp) }
                }
                Spacer(Modifier.width(5.dp))
                SoftButton(onClick = { adding = true }) { Text("+ 종목", fontSize = 12.sp) }
            }
        }
        item { IndexPanel(state) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val head = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Text("종목명", Modifier.weight(W_NAME), style = head, color = color)
                Text("현재가 / 대비", Modifier.weight(W_PRICE), style = head, color = color, textAlign = TextAlign.End)
                Text("등락률", Modifier.weight(W_RATE), style = head, color = color, textAlign = TextAlign.End)
                Text("1년", Modifier.weight(W_CHART), style = head, color = color, textAlign = TextAlign.End)
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

/** 코스피·코스닥은 넓은 칸(이름 왼쪽·가격 오른쪽), 나머지 셋은 세로로 쌓은 칸. */
@Composable
private fun IndexPanel(state: MarketUiState) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MARKET_PANEL.take(2).forEach { IndexTile(it, state.quotes[it.symbol], wide = true, Modifier.weight(1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MARKET_PANEL.drop(2).forEach { IndexTile(it, state.quotes[it.symbol], wide = false, Modifier.weight(1f)) }
        }
        Text(
            stamp(state.quotes["^KS11"]) + " · 해외 지수·환율은 지연될 수 있음",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IndexTile(item: Instrument, quote: Quote?, wide: Boolean, modifier: Modifier = Modifier) {
    val color = changeColor(quote?.change)
    val price = quote?.let { priceText(item, it.price) } ?: "—"
    val delta = quote?.change?.let { "${signed(it, 2)} (${signed(quote.changePercent ?: 0.0, 2, "%")})" } ?: "전일 대비 —"
    val nameStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(item.name, style = nameStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Column(horizontalAlignment = Alignment.End) {
                    Text(price, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp), color = color, maxLines = 1)
                    Text(delta, Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
                }
            }
        } else {
            Text(item.name, style = nameStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(price, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!wide) Text(delta, Modifier.padding(top = 3.dp), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(W_NAME)) {
            Text(item.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (when (item.type) { Instrument.TYPE_ETF -> "ETF "; Instrument.TYPE_INDEX -> "지수 "; else -> "주식 " }) +
                    item.symbol.replace(Regex("\\.(KS|KQ)$"), "").removePrefix("^"),
                Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(Modifier.weight(W_PRICE), horizontalAlignment = Alignment.End) {
            Text(quote?.let { priceText(item, it.price) } ?: "—", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp), color = color, maxLines = 1)
            Text(quote?.change?.let { signed(it, changeDigits(item)) } ?: "—", Modifier.padding(top = 5.dp), style = TextStyle(fontSize = 10.sp), color = color, maxLines = 1)
        }
        Text(
            quote?.changePercent?.let { "(${signed(it, 2, "%")})" } ?: "—",
            Modifier.weight(W_RATE), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1, textAlign = TextAlign.End
        )
        Column(Modifier.weight(W_CHART), horizontalAlignment = Alignment.End) {
            if (history == null) {
                Text("차트 불러오는 중…", style = TextStyle(fontSize = 8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            } else {
                Sparkline(history.closes, Modifier.fillMaxWidth().height(32.dp))
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("고 ${compactPrice(item, history.high)}", style = TextStyle(fontSize = 9.sp), color = UpColor, maxLines = 1)
                    Text("저 ${compactPrice(item, history.low)}", style = TextStyle(fontSize = 9.sp), color = DownColor, maxLines = 1)
                }
            }
        }
        if (editing) {
            Column {
                Row {
                    IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ArrowUpward, contentDescription = "위로") }
                    IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ArrowDownward, contentDescription = "아래로") }
                }
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.End).size(34.dp)) {
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
