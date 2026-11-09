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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.DartApi
import io.github.hdlee73.financenewsradar.data.DartCompany
import io.github.hdlee73.financenewsradar.data.DartCorp
import io.github.hdlee73.financenewsradar.data.DartFilings
import io.github.hdlee73.financenewsradar.data.DartFiling
import io.github.hdlee73.financenewsradar.data.DartFinance
import io.github.hdlee73.financenewsradar.data.DartHolder
import io.github.hdlee73.financenewsradar.data.DartHolders
import io.github.hdlee73.financenewsradar.data.DartShare
import io.github.hdlee73.financenewsradar.data.DartStore
import io.github.hdlee73.financenewsradar.data.IntradaySeries
import io.github.hdlee73.financenewsradar.data.MarketClient
import io.github.hdlee73.financenewsradar.data.dartMarketLabel
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val UpRed = Color(0xFFDC5C60)
private val DownBlue = Color(0xFF4B7BC8)

/** 시장 단위(법인구분)별 색. */
private fun marketColor(cls: String): Color = when (cls) {
    "Y" -> Color(0xFF2A7DE1)
    "K" -> Color(0xFF2E9E6B)
    "N" -> Color(0xFF7B57C9)
    else -> Color(0xFF8A94A6)
}

private fun clsOf(corp: DartCorp): String = corp.cls.ifBlank { if (corp.stock.isBlank()) "E" else "Y" }

/** 불러오는 중 / 완료 / 실패. */
sealed interface DartLoad<out T> {
    data object Loading : DartLoad<Nothing>
    data class Done<T>(val value: T) : DartLoad<T>
    data class Failed(val message: String) : DartLoad<Nothing>
}

/** 주가 한 종목: 현재가·전일 종가와 최근 1년 일별 종가. */
data class DartStock(val price: Double, val previous: Double?, val closes: List<Double>, val high: Double, val low: Double) {
    val change: Double? get() = previous?.takeIf { it > 0 }?.let { price - it }
    val percent: Double? get() = previous?.takeIf { it > 0 }?.let { (price - it) / it * 100 }
}

/** 재무손익 탭에 보여 줄 값: 재무 자료와 연말 종가(PER 계산용). */
data class DartFinView(val finance: DartFinance, val closes: Map<Int, Double>)

/** 관심기업 새 공시 한 줄. */
data class DartFeedItem(val corp: DartCorp, val filing: DartFiling)

data class DartUiState(
    val query: String = "",
    val results: DartLoad<List<DartCorp>>? = null,
    val watch: List<DartCorp> = emptyList(),
    val recent: List<String> = emptyList(),
    /** 관심기업별 사용자가 아직 보지 않은 공시 건수. */
    val newCount: Map<String, Int> = emptyMap(),
    val feed: List<DartFeedItem> = emptyList(),
    val feedLoading: Boolean = false,
    val selected: DartCorp? = null,
    val company: Map<String, DartLoad<DartCompany>> = emptyMap(),
    val holders: Map<String, DartLoad<DartHolders>> = emptyMap(),
    val finance: Map<String, DartLoad<DartFinView>> = emptyMap(),
    /** 키는 "회사코드|a·f·e|시작|끝". */
    val filings: Map<String, DartLoad<DartFilings>> = emptyMap(),
    /** 키는 "회사코드|시작|끝". */
    val stocks: Map<String, DartLoad<DartStock>> = emptyMap(),
    val intraday: Map<String, DartLoad<IntradaySeries>> = emptyMap()
)

private const val MAX_WATCH = 12
private const val MAX_RECENT = 8

private val DAY = SimpleDateFormat("yyyyMMdd", Locale.US)
private fun today(): String = DAY.format(Calendar.getInstance().time)
private fun daysAgo(n: Int): String = DAY.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)
private fun yearsAgo(n: Int): String = DAY.format(Calendar.getInstance().apply { add(Calendar.YEAR, -n) }.time)

/** yyyyMM 값을 월 단위로 더한다. */
private fun addMonths(ym: Int, delta: Int): Int {
    val total = (ym / 100) * 12 + (ym % 100 - 1) + delta
    return (total / 12) * 100 + total % 12 + 1
}

private fun nowYm(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 100 + it.get(Calendar.MONTH) + 1 }

/** 월 마지막 날을 포함한 yyyyMMdd. */
private fun monthEnd(ym: Int): String {
    val c = Calendar.getInstance().apply { clear(); set(ym / 100, ym % 100 - 1, 1) }
    return String.format(Locale.US, "%d%02d%02d", ym / 100, ym % 100, c.getActualMaximum(Calendar.DAY_OF_MONTH))
}

private fun monthStart(ym: Int): String = String.format(Locale.US, "%d%02d01", ym / 100, ym % 100)

/** 조회 기간: 0 = 1년, 1 = 3년, 2 = 5년, 3 = 직접 선택(최대 10년). */
private data class DartPeriod(val index: Int, val fromYm: Int, val toYm: Int) {
    val from: String get() = when (index) { 0 -> yearsAgo(1); 1 -> yearsAgo(3); 2 -> yearsAgo(5); else -> monthStart(fromYm) }
    val to: String get() = if (index == 3) minOf(monthEnd(toYm), today()) else today()
}

class DartViewModel(application: Application) : AndroidViewModel(application) {
    private val api = DartApi()
    private val store = DartStore(application)
    private val market = MarketClient(application)
    val isConfigured: Boolean get() = api.isConfigured
    private val _state = MutableStateFlow(DartUiState(watch = store.watch(), recent = store.recent()))
    val state: StateFlow<DartUiState> = _state.asStateFlow()

    init {
        if (api.isConfigured && _state.value.watch.isNotEmpty()) refreshFeed()
    }

    fun search(raw: String) {
        val q = raw.trim()
        _state.update { it.copy(query = raw) }
        if (q.isEmpty()) {
            _state.update { it.copy(results = null) }
            return
        }
        _state.update { it.copy(results = DartLoad.Loading) }
        viewModelScope.launch {
            runCatching { api.search(q) }
                .onSuccess { list ->
                    val recent = (listOf(q) + _state.value.recent.filter { it != q }).take(MAX_RECENT)
                    store.saveRecent(recent)
                    _state.update { s -> if (s.query.trim() == q) s.copy(results = DartLoad.Done(list), recent = recent) else s }
                }
                .onFailure { e -> _state.update { s -> if (s.query.trim() == q) s.copy(results = DartLoad.Failed(e.message ?: "검색에 실패했습니다.")) else s } }
        }
    }

    fun clearSearch() = _state.update { it.copy(query = "", results = null) }

    fun open(corp: DartCorp) {
        _state.update { it.copy(selected = corp) }
        markSeen(corp)
    }

    fun back() = _state.update { it.copy(selected = null) }

    fun toggleWatch(corp: DartCorp) {
        val current = _state.value.watch
        val next = if (current.any { it.corp == corp.corp }) current.filter { it.corp != corp.corp }
        else (current + corp).takeLast(MAX_WATCH).also { store.setSeen(corp.corp, today()) }
        store.saveWatch(next)
        _state.update { it.copy(watch = next, newCount = it.newCount.filterKeys { k -> next.any { c -> c.corp == k } }, feed = it.feed.filter { f -> next.any { c -> c.corp == f.corp.corp } }) }
        if (next.size > current.size) ensureStock(next.last())
    }

    /** 상세를 열 때: 개황을 받고, 시장 구분을 알게 되면 관심기업 정보도 보강한다. */
    fun ensureCompany(corp: DartCorp) {
        if (_state.value.company[corp.corp] is DartLoad.Done) return
        _state.update { it.copy(company = it.company + (corp.corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { api.company(corp.corp) }
                .onSuccess { c ->
                    _state.update { it.copy(company = it.company + (corp.corp to DartLoad.Done(c))) }
                    val full = corp.copy(name = c.name.ifBlank { corp.name }, stock = c.stock, cls = c.cls)
                    if (_state.value.watch.any { it.corp == corp.corp && it.cls != c.cls }) {
                        val next = _state.value.watch.map { if (it.corp == corp.corp) full else it }
                        store.saveWatch(next)
                        _state.update { it.copy(watch = next) }
                    }
                    ensureStock(full)
                }
                .onFailure { e -> _state.update { it.copy(company = it.company + (corp.corp to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    fun ensureStock(corp: DartCorp, force: Boolean = false) {
        val symbol = corp.symbol ?: return
        if (!force && _state.value.stocks[corp.corp].let { it is DartLoad.Done || it is DartLoad.Loading }) return
        _state.update { it.copy(stocks = it.stocks + (corp.corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val quote = async { market.quote(symbol) }
                    val history = async { runCatching { market.history(symbol) }.getOrNull() }
                    val q = quote.await()
                    val h = history.await()
                    DartStock(q.price, q.previous, h?.closes.orEmpty(), h?.high ?: q.price, h?.low ?: q.price)
                }
            }
                .onSuccess { s -> _state.update { it.copy(stocks = it.stocks + (corp.corp to DartLoad.Done(s))) } }
                .onFailure { e -> _state.update { it.copy(stocks = it.stocks + (corp.corp to DartLoad.Failed(e.message ?: "시세를 불러오지 못했습니다."))) } }
        }
    }

    /** 당일(장이 끝났으면 가장 최근 거래일) 분봉. */
    fun ensureIntraday(corp: DartCorp, previous: Double?, force: Boolean = false) {
        val symbol = corp.symbol ?: return
        if (!force && _state.value.intraday[corp.corp].let { it is DartLoad.Done || it is DartLoad.Loading }) return
        _state.update { it.copy(intraday = it.intraday + (corp.corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { market.intraday(symbol, previous) }
                .onSuccess { s -> _state.update { it.copy(intraday = it.intraday + (corp.corp to DartLoad.Done(s))) } }
                .onFailure { e -> _state.update { it.copy(intraday = it.intraday + (corp.corp to DartLoad.Failed(e.message ?: "당일 시세를 불러오지 못했습니다."))) } }
        }
    }

    fun ensureHolders(corp: String, force: Boolean = false) {
        if (!force && _state.value.holders[corp] is DartLoad.Done) return
        _state.update { it.copy(holders = it.holders + (corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { api.holders(corp) }
                .onSuccess { h -> _state.update { it.copy(holders = it.holders + (corp to DartLoad.Done(h))) } }
                .onFailure { e -> _state.update { it.copy(holders = it.holders + (corp to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    fun ensureFinance(corp: DartCorp, force: Boolean = false) {
        if (!force && _state.value.finance[corp.corp] is DartLoad.Done) return
        _state.update { it.copy(finance = it.finance + (corp.corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val closes = async { if (corp.stock.length == 6) api.yearEndCloses(corp.stock) else emptyMap() }
                    val finance = api.finance(corp.corp)
                    DartFinView(finance, closes.await())
                }
            }
                .onSuccess { v -> _state.update { it.copy(finance = it.finance + (corp.corp to DartLoad.Done(v))) } }
                .onFailure { e -> _state.update { it.copy(finance = it.finance + (corp.corp to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    fun ensureFilings(corp: String, type: String, period: DartPeriodArg, force: Boolean = false) {
        val key = "$corp|$type|${period.from}|${period.to}"
        if (!force && _state.value.filings[key].let { it is DartLoad.Done || it is DartLoad.Loading }) return
        _state.update { it.copy(filings = it.filings + (key to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { api.filings(corp, type, period.from, period.to) }
                .onSuccess { f -> _state.update { it.copy(filings = it.filings + (key to DartLoad.Done(f))) } }
                .onFailure { e -> _state.update { it.copy(filings = it.filings + (key to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    /** 이 회사의 공시를 확인했다고 기록한다(새 공시 표시 해제). */
    private fun markSeen(corp: DartCorp) {
        store.setSeen(corp.corp, today())
        _state.update { it.copy(newCount = it.newCount - corp.corp) }
    }

    /** 관심기업의 정기·기타 공시를 모아 최근 7일 새 공시를 만든다. */
    fun refreshFeed() {
        val watch = _state.value.watch
        if (watch.isEmpty() || !api.isConfigured) return
        _state.update { it.copy(feedLoading = true) }
        viewModelScope.launch {
            val since = daysAgo(7)
            val from = yearsAgo(1)
            val to = today()
            val collected = runCatching {
                coroutineScope {
                    watch.map { corp ->
                        async {
                            val a = runCatching { api.filings(corp.corp, "a", from, to).items }.getOrDefault(emptyList())
                            val m = runCatching { api.filings(corp.corp, "m", daysAgo(60), to).items }.getOrDefault(emptyList())
                            val e = runCatching { api.filings(corp.corp, "e", daysAgo(60), to).items }.getOrDefault(emptyList())
                            (a + m + e).map { DartFeedItem(corp, it) }
                        }
                    }.awaitAll().flatten()
                }
            }.getOrDefault(emptyList())
            val fresh = collected.filter { it.filing.date >= since }.sortedByDescending { it.filing.date }
            val counts = watch.associate { corp ->
                val seen = store.seen(corp.corp)
                corp.corp to collected.count { it.corp.corp == corp.corp && it.filing.date > seen }
            }.filterValues { it > 0 }
            _state.update { it.copy(feed = fresh.take(10), newCount = counts, feedLoading = false) }
            watch.forEach { ensureStock(it) }
        }
    }
}

/** 화면에서 ViewModel로 넘기는 조회 기간(yyyyMMdd). */
data class DartPeriodArg(val from: String, val to: String)

private fun dateText(yyyymmdd: String): String =
    if (yyyymmdd.length == 8) "${yyyymmdd.substring(0, 4)}.${yyyymmdd.substring(4, 6)}.${yyyymmdd.substring(6, 8)}" else yyyymmdd

private fun grouped(value: Double, digits: Int = 0): String = String.format(Locale.US, "%,.${digits}f", value)

private fun signText(value: Double, digits: Int = 0): String = (if (value > 0) "▲" else if (value < 0) "▼" else "") + grouped(kotlin.math.abs(value), digits)

/** 억원 값: 100억 이상은 정수, 그보다 작으면 소수 한 자리. */
private fun eokText(v: Double?): String = when {
    v == null -> "-"
    kotlin.math.abs(v) >= 100 -> grouped(v, 0)
    else -> grouped(v, 1)
}

private fun percentText(v: Double?, digits: Int = 1): String = if (v == null) "-" else String.format(Locale.US, "%,.${digits}f%%", v)

private fun dartChangeColor(change: Double?): Color = when {
    change == null || change == 0.0 -> Color.Unspecified
    change > 0 -> UpRed
    else -> DownBlue
}

/** 시장 단위 이름표(유가증권·코스닥·코넥스·기타법인)를 색으로 구분해 보여 준다. */
@Composable
private fun MarketChip(cls: String, modifier: Modifier = Modifier) {
    val color = marketColor(cls)
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Text(dartMarketLabel(cls), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 기업 개황 및 공시(DART): 회사를 검색해 개황·최대주주·재무손익·정기/주요사항/기타 공시와 주가를 보고, 관심기업을 모아 새 공시를 확인한다. */
@Composable
fun DartScreen(
    viewModel: DartViewModel,
    modifier: Modifier = Modifier,
    /** 다른 화면(회계감리·공시조사결과제재)에서 회사명을 눌러 들어온 검색 요청. 받으면 그 이름으로 바로 검색한다. */
    searchRequest: String? = null,
    onSearchRequestConsumed: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.selected
    BackHandler(enabled = selected != null) { viewModel.back() }
    LaunchedEffect(searchRequest) {
        val request = searchRequest?.trim().orEmpty()
        if (request.isNotEmpty()) {
            viewModel.back()
            viewModel.search(request)
        }
        if (searchRequest != null) onSearchRequestConsumed()
    }
    Column(modifier.fillMaxSize()) {
        if (selected == null) {
            LargeTitle("기업 개황 및 공시(DART)", subtitle = "회사명·종목코드로 검색 · 관심기업의 새 공시 모아 보기")
            if (!viewModel.isConfigured) CenterMessage("서버가 연결되지 않아 공시를 불러올 수 없습니다.")
            else DartHome(state, viewModel)
        } else {
            DartDetail(selected, state, viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DartHome(state: DartUiState, viewModel: DartViewModel) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    // 다른 화면에서 들어와 검색어가 바뀌었을 때(또는 지워졌을 때) 입력창도 맞춘다.
    var seenQuery by remember { mutableStateOf(state.query) }
    LaunchedEffect(state.query) { if (state.query != seenQuery) { seenQuery = state.query; query = state.query } }
    Column(Modifier.fillMaxSize()) {
        SearchPill(
            value = query, onValueChange = { query = it; if (it.isBlank()) viewModel.clearSearch() },
            placeholder = "회사명 또는 종목코드 검색", onSearch = { viewModel.search(query) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), description = "기업 검색"
        )
        val results = state.results
        if (results != null) {
            when (results) {
                DartLoad.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                is DartLoad.Failed -> CenterMessage(results.message, isError = true, actionLabel = "다시 시도", onAction = { viewModel.search(query) })
                is DartLoad.Done -> if (results.value.isEmpty()) CenterMessage("검색 결과가 없습니다. 상장사·외부감사 대상 법인 위주로 찾습니다.")
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(results.value, key = { it.corp }) { corp ->
                        ListCard(onClick = { viewModel.open(corp) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                InitialAvatar(corp.name, marketColor(clsOf(corp)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(corp.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MarketChip(clsOf(corp))
                                        if (corp.stock.isNotBlank()) Text(corp.stock, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { viewModel.toggleWatch(corp) }) {
                                    val on = state.watch.any { it.corp == corp.corp }
                                    Icon(if (on) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = if (on) "관심기업 해제" else "관심기업 추가", tint = if (on) AppColors.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("★ 관심기업" + if (state.watch.isEmpty()) "" else " ${state.watch.size}곳", action = {
                if (state.watch.isNotEmpty()) TextButton(onClick = viewModel::refreshFeed) { Text("새로고침") }
            })
            if (state.watch.isEmpty()) {
                Text("검색 결과의 별(★)을 눌러 관심기업을 추가하면 새 공시와 주가를 한곳에서 볼 수 있습니다.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.watch.forEach { corp ->
                        val n = state.newCount[corp.corp] ?: 0
                        PillChip(corp.name + if (n > 0) "  N" else "", onClick = { viewModel.open(corp) }, onPanel = true)
                    }
                }
                SectionLabel("관심기업 새 공시 · 최근 7일", action = { if (state.feedLoading) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(16.dp), strokeWidth = 2.dp) })
                if (state.feed.isEmpty() && !state.feedLoading) {
                    Text("최근 7일 새 공시가 없습니다.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val uri = LocalUriHandler.current
                state.feed.forEach { item ->
                    ListCard(onClick = { uri.openUri(item.filing.url) }) {
                        Text(item.filing.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${item.corp.name} · ${dateText(item.filing.date)} · ${item.filing.src}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.recent.isNotEmpty()) {
                SectionLabel("최근 검색")
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recent.forEach { word -> PillChip(word, onClick = { query = word; viewModel.search(word) }, onPanel = true) }
                }
            }
            Text("DART(금융감독원 전자공시) 자료이며 주가는 지연될 수 있습니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val DART_TABS = listOf("개황", "최대주주", "재무손익", "정기보고서", "주요사항보고서", "기타공시")

@Composable
private fun DartDetail(corp: DartCorp, state: DartUiState, viewModel: DartViewModel) {
    LaunchedEffect(corp.corp) { viewModel.ensureCompany(corp) }
    var tab by rememberSaveable(corp.corp) { mutableIntStateOf(0) }
    val watched = state.watch.any { it.corp == corp.corp }
    val companyLoad = state.company[corp.corp]
    val company = (companyLoad as? DartLoad.Done)?.value
    val effective = if (company != null) corp.copy(name = company.name.ifBlank { corp.name }, stock = company.stock, cls = company.cls) else corp
    BackHeader(corp.name, onBack = viewModel::back) {
        IconButton(onClick = { viewModel.toggleWatch(effective) }) {
            Icon(if (watched) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = if (watched) "관심기업 해제" else "관심기업 추가")
        }
    }
    LaunchedEffect(tab, corp.corp) {
        when (tab) {
            1 -> viewModel.ensureHolders(corp.corp)
            2 -> viewModel.ensureFinance(effective)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarketChip(clsOf(effective))
            if (effective.stock.isNotBlank()) Text(effective.stock, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (effective.stock.isNotBlank()) StockCard(effective, state, viewModel)
        UnderlineTabs(DART_TABS, tab, { tab = it })
        when (tab) {
            0 -> OverviewTab(companyLoad, onRetry = { viewModel.ensureCompany(corp) })
            1 -> HoldersTab(state.holders[corp.corp], onRetry = { viewModel.ensureHolders(corp.corp, force = true) })
            2 -> FinanceTab(state.finance[corp.corp], onRetry = { viewModel.ensureFinance(effective, force = true) })
            3 -> FilingsTab(effective, "a", "정기", "기간 안에 정기보고서(사업·반기·분기)가 없습니다.", state, viewModel)
            4 -> FilingsTab(effective, "m", "주요", "기간 안에 주요사항보고서가 없습니다.", state, viewModel)
            else -> OtherTab(effective, state, viewModel)
        }
    }
}

private fun monthsAgo(n: Int): String = DAY.format(Calendar.getInstance().apply { add(Calendar.MONTH, -n) }.time)

/** 기간별 차트 시작 날짜(달력 기준, 휴장일이면 그 뒤 첫 거래일부터라 며칠 차이날 수 있다). */
private val STOCK_START: List<() -> String> = listOf({ today() }, { daysAgo(7) }, { monthsAgo(1) }, { monthsAgo(3) }, { yearsAgo(1) })

private val STOCK_PERIODS = listOf("1일" to 1, "1주" to 5, "1개월" to 21, "3개월" to 63, "1년" to 260)

@Composable
private fun StockCard(corp: DartCorp, state: DartUiState, viewModel: DartViewModel) {
    var period by rememberSaveable { mutableIntStateOf(3) }
    val load = state.stocks[corp.corp]
    ListCard {
        Text("주가", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (load) {
            null, DartLoad.Loading -> Box(Modifier.fillMaxWidth().height(70.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            is DartLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시세를 불러오지 못했습니다.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.ensureStock(corp, force = true) }) { Text("다시 시도") }
            }
            is DartLoad.Done -> {
                val s = load.value
                val tint = dartChangeColor(s.change).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(grouped(s.price) + "원", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (s.change != null && s.percent != null) {
                        Text("${signText(s.change!!)} (${if (s.percent!! > 0) "+" else ""}${String.format(Locale.US, "%.2f", s.percent)}%)", color = tint, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
                val (label, count) = STOCK_PERIODS[period]
                if (period == 0) {
                    LaunchedEffect(corp.corp) { viewModel.ensureIntraday(corp, s.previous) }
                    when (val d = state.intraday[corp.corp]) {
                        null, DartLoad.Loading -> Box(Modifier.fillMaxWidth().height(64.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp) }
                        is DartLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { viewModel.ensureIntraday(corp, s.previous, force = true) }) { Text("다시 시도") }
                        }
                        is DartLoad.Done -> {
                            val pts = d.value.points
                            val up = pts.last() >= (d.value.previous ?: pts.first())
                            IntradayChart(d.value, if (up) UpRed else DownBlue, Modifier.fillMaxWidth().height(80.dp), compare = false)
                        }
                    }
                } else {
                    val slice = s.closes.takeLast(count)
                    if (slice.size >= 2) PriceLine(slice, if (slice.last() >= slice.first()) UpRed else DownBlue, Modifier.fillMaxWidth().height(64.dp))
                }
                val startText: String? = if (period == 0) {
                    (state.intraday[corp.corp] as? DartLoad.Done)?.value?.points?.firstOrNull()?.let { "시작 시가 ${grouped(it)}" }
                } else {
                    s.closes.takeLast(count).takeIf { it.size >= 2 }?.let { "시작 ${dateText(STOCK_START[period]())} · ${grouped(it.first())}" }
                }
                if (startText != null) Text(startText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STOCK_PERIODS.forEachIndexed { i, (name, _) -> PillChip(name, onClick = { period = i }, selected = i == period, onPanel = true) }
                }
                val slice = s.closes.takeLast(count)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("52주 최저 ${grouped(s.low)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (period != 0 && slice.size >= 2) {
                        Text("$label ${String.format(Locale.US, "%+.1f", (slice.last() / slice.first() - 1) * 100)}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("52주 최고 ${grouped(s.high)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${corp.stock} · 시세 제공처 기준, 지연될 수 있음" + if (period == 0) " · 1일은 장이 끝났으면 가장 최근 거래일 흐름" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PriceLine(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i / (values.size - 1f) * size.width
            val y = size.height - ((v - lo) / span).toFloat() * size.height * 0.9f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, size.height - ((values.last() - lo) / span).toFloat() * size.height * 0.9f - size.height * 0.05f))
    }
}

@Composable
private fun <T> LoadBox(load: DartLoad<T>?, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        null, DartLoad.Loading -> Box(Modifier.fillMaxWidth().height(120.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        is DartLoad.Failed -> CenterMessage(load.message, isError = true, actionLabel = "다시 시도", onAction = onRetry, modifier = Modifier.fillMaxWidth().height(180.dp))
        is DartLoad.Done -> content(load.value)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun OverviewTab(load: DartLoad<DartCompany>?, onRetry: () -> Unit) {
    LoadBox(load, onRetry) { c ->
        val uri = LocalUriHandler.current
        ListCard {
            InfoLine("회사명", c.name)
            InfoLine("영문명", c.nameEn)
            InfoLine("종목코드", c.stock.ifBlank { "-" })
            InfoLine("시장", dartMarketLabel(c.cls.ifBlank { "E" }))
            InfoLine("대표이사", c.ceo)
            InfoLine("법인등록번호", c.corpNo)
            InfoLine("사업자등록번호", c.bizNo)
            InfoLine("설립일", dateText(c.established))
            InfoLine("결산월", c.accountMonth.takeIf { it.isNotBlank() }?.let { "${it}월" }.orEmpty())
            InfoLine("업종코드", c.industryCode)
            InfoLine("주소", c.address)
            InfoLine("전화", c.phone)
            if (c.homepage.isNotBlank()) {
                val url = if (c.homepage.startsWith("http")) c.homepage else "https://${c.homepage}"
                Text("홈페이지: ${c.homepage}", Modifier.clickable { runCatching { uri.openUri(url) } }.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("출처: DART 기업개황", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShareCell(share: DartShare?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(share?.ratio?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(share?.shares?.let { grouped(it) + "주" } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HoldersTab(load: DartLoad<DartHolders>?, onRetry: () -> Unit) {
    LoadBox(load, onRetry) { h ->
        if (h.items.isEmpty()) {
            CenterMessage("최근 정기보고서에서 최대주주 현황을 찾지 못했습니다. 정기보고서 제출 대상이 아닌 회사일 수 있습니다.", modifier = Modifier.fillMaxWidth().height(180.dp))
            return@LoadBox
        }
        ListCard {
            Text("${h.basis} 기준", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("최대주주 및 특수관계인 합계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("보통주 ${percentText(h.totalCommon, 2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (h.totalPref != null) Text("우선주 ${percentText(h.totalPref, 2)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("주주", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("보통주", Modifier.width(104.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            Text("우선주", Modifier.width(104.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        h.items.forEach { holder: DartHolder ->
            ListCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(holder.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (holder.relation.isNotBlank() && holder.relation != "-") Text(holder.relation, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ShareCell(holder.common, Modifier.width(104.dp))
                    ShareCell(holder.pref, Modifier.width(104.dp))
                }
                val ratio = (holder.common?.ratio ?: 0.0).coerceIn(0.0, 100.0)
                Box(Modifier.fillMaxWidth().height(6.dp).clipBar(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((ratio / 100.0).toFloat().coerceAtLeast(0.01f)).height(6.dp).clipBar(AppColors.heroEnd))
                }
            }
        }
        Text("출처: DART 최대주주 현황(정기보고서). 같은 주주의 보통주·우선주는 한 줄로 합쳐 보여 줍니다. 막대는 보통주 지분입니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Modifier.clipBar(color: Color): Modifier = this
    .clip(RoundedCornerShape(3.dp))
    .background(color)

// ───────── 재무손익 현황 ─────────

private val BarRevenue = Color(0xFF2A7DE1)
private val BarOp = Color(0xFFF08A4B)
private val BarNet = Color(0xFF3F5A82)

@Composable
private fun TableHeader(cols: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("구분", Modifier.weight(0.9f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        cols.forEach { (label, sub) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, fontSize = 12.sp, lineHeight = 14.sp)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun TableRow(label: String, values: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        values.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, maxLines = 1, softWrap = false, fontSize = 13.sp) }
    }
}

@Composable
private fun FinanceTab(load: DartLoad<DartFinView>?, onRetry: () -> Unit) {
    LoadBox(load, onRetry) { view ->
        val f = view.finance
        if (f.balance.isEmpty() && f.income.isEmpty()) {
            CenterMessage(f.note.ifBlank { "재무 자료를 찾지 못했습니다. 정기보고서 제출 대상이 아닌 회사일 수 있습니다." }, modifier = Modifier.fillMaxWidth().height(180.dp))
            return@LoadBox
        }
        ListCard {
            BlockTitle("재무상태 비교")
            Text("${f.basis}재무제표 · 단위 억원", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TableHeader(f.balance.map { it.label to "" })
            TableRow("자산총계", f.balance.map { eokText(it.assets) })
            TableRow("부채총계", f.balance.map { eokText(it.liab) })
            TableRow("자본총계", f.balance.map { eokText(it.equity) })
            TableRow("부채비율", f.balance.map { c -> percentText(if (c.equity != null && c.equity != 0.0 && c.liab != null) c.liab / c.equity * 100 else null) })
            Text("부채비율 = 부채총계 ÷ 자본총계", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ListCard {
            BlockTitle("손익현황")
            Text("출처: DART 정기보고서 · 단위 억원", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TableHeader(f.income.map { it.label to it.sub })
            TableRow("매출액", f.income.map { eokText(it.revenue) })
            TableRow("영업이익", f.income.map { eokText(it.op) })
            TableRow("당기순이익", f.income.map { eokText(it.net) })
            Text("금융회사는 영업수익 등 업종별 계정을 매출액 줄에 보여 주며, 해당 계정이 없으면 -로 표시됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TrendCard(view)
        PerShareCard(view)
        Text("출처: DART 주요계정·전체 재무제표. 연말 종가는 시세 제공처 기준입니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BlockTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun TrendCard(view: DartFinView) {
    val trend = view.finance.trend
    if (trend.size < 2) return
    var selected by rememberSaveable { mutableIntStateOf(trend.lastIndex) }
    val sel = trend[selected.coerceIn(0, trend.lastIndex)]
    ListCard {
        BlockTitle("최근 ${trend.size}개년 추이")
        Text("출처: DART 사업보고서 · ${view.finance.basis}재무제표 · 단위 억원 · 연도를 누르면 수치 표시", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(BarRevenue, "매출액")
            LegendDot(BarOp, "영업이익")
            LegendDot(BarNet, "당기순이익")
        }
        Text("${sel.year}년 매출액 ${eokText(sel.revenue)}억 · 영업이익 ${eokText(sel.op)}억 · 당기순이익 ${eokText(sel.net)}억", style = MaterialTheme.typography.bodySmall)
        BarChart(trend, selected, Modifier.fillMaxWidth().height(130.dp))
        YearRow(trend.map { it.year }, selected) { selected = it }
        Text("${sel.year}년 부채비율(%) ${percentText(sel.debt)}", style = MaterialTheme.typography.bodySmall)
        DebtLine(trend, selected, Modifier.fillMaxWidth().height(70.dp))
        YearRow(trend.map { it.year }, selected) { selected = it }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun YearRow(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        years.forEachIndexed { i, y ->
            Text(
                "$y", Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 4.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BarChart(points: List<io.github.hdlee73.financenewsradar.data.DartTrendPoint>, selected: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val all = points.flatMap { listOfNotNull(it.revenue, it.op, it.net) }
        if (all.isEmpty()) return@Canvas
        val hi = maxOf(all.max(), 0.0)
        val lo = minOf(all.min(), 0.0)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun yOf(v: Double) = ((hi - v) / span).toFloat() * size.height
        val zero = yOf(0.0)
        val groupW = size.width / points.size
        val barW = groupW * 0.22f
        points.forEachIndexed { i, p ->
            val left = i * groupW + groupW * 0.5f - barW * 1.5f
            listOf(p.revenue to BarRevenue, p.op to BarOp, p.net to BarNet).forEachIndexed { j, (v, color) ->
                if (v == null) return@forEachIndexed
                val y = yOf(v)
                val top = minOf(y, zero)
                val h = kotlin.math.abs(y - zero).coerceAtLeast(1f)
                drawRect(color.copy(alpha = if (i == selected) 1f else 0.45f), Offset(left + j * barW, top), Size(barW * 0.92f, h))
            }
        }
        drawLine(Color(0x33000000), Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun DebtLine(points: List<io.github.hdlee73.financenewsradar.data.DartTrendPoint>, selected: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val vals = points.map { it.debt }
        val known = vals.filterNotNull()
        if (known.size < 2) return@Canvas
        val lo = known.min()
        val span = (known.max() - lo).takeIf { it > 0 } ?: 1.0
        val groupW = size.width / points.size
        fun xOf(i: Int) = i * groupW + groupW / 2
        fun yOf(v: Double) = size.height - ((v - lo) / span).toFloat() * size.height * 0.8f - size.height * 0.1f
        val path = Path()
        var started = false
        vals.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) { path.moveTo(xOf(i), yOf(v)); started = true } else path.lineTo(xOf(i), yOf(v))
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        vals.forEachIndexed { i, v -> if (v != null) drawCircle(line, radius = if (i == selected) 5.dp.toPx() else 3.dp.toPx(), center = Offset(xOf(i), yOf(v))) }
    }
}

@Composable
private fun PerShareCard(view: DartFinView) {
    val eps = view.finance.eps
    val years = view.finance.trend.map { it.year }.filter { eps.containsKey(it) }.takeLast(4)
    if (years.isEmpty()) return
    ListCard {
        BlockTitle("주당·배수 지표")
        TableHeader(years.map { "${it}년" to "" })
        TableRow("EPS(원)", years.map { grouped(eps.getValue(it)) })
        if (view.closes.isNotEmpty()) {
            TableRow("연말 종가(원)", years.map { y -> view.closes[y]?.let { grouped(it) } ?: "-" })
            TableRow("PER(배)", years.map { y ->
                val close = view.closes[y]
                val e = eps[y]
                if (close != null && e != null && e > 0) String.format(Locale.US, "%.2f", close / e) else "-"
            })
        }
        Text("EPS는 DART 공시값입니다. PER = 연말 종가 ÷ EPS로 직접 계산한 추정치라 증권사 표시값과 다를 수 있습니다. 적자(EPS 0 이하)는 -로 표시합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────── 공시 목록(기간 선택) ─────────

@Composable
private fun FilingList(items: List<DartFiling>, tagFallback: String) {
    val uri = LocalUriHandler.current
    var shown by rememberSaveable { mutableIntStateOf(50) }
    items.take(shown).forEach { f ->
        ListCard(onClick = { runCatching { uri.openUri(f.url) } }) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagLabel(tagFallback, accent = false)
                Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text(listOf(dateText(f.date), f.by, f.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (items.size > shown) TextButton(onClick = { shown += 50 }, modifier = Modifier.fillMaxWidth()) { Text("더 보기 (${items.size - shown}건 남음)") }
}

private val PERIOD_LABELS = listOf("1년", "3년", "5년", "기간선택")

/** 기간 칩(1년·3년·5년·기간선택). 기간선택을 누르면 시작·종료 연월을 고르는 창이 열린다(최대 10년). */
@Composable
private fun PeriodBar(period: DartPeriod, onChange: (DartPeriod) -> Unit) {
    var dialog by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PERIOD_LABELS.forEachIndexed { i, label ->
            val text = if (i == 3 && period.index == 3) "${period.fromYm / 100}.${String.format(Locale.US, "%02d", period.fromYm % 100)}~${period.toYm / 100}.${String.format(Locale.US, "%02d", period.toYm % 100)}" else label
            PillChip(text, onClick = { if (i == 3) dialog = true else onChange(period.copy(index = i)) }, selected = i == period.index, onPanel = true)
        }
    }
    if (dialog) {
        var from by rememberSaveable { mutableIntStateOf(if (period.index == 3) period.fromYm else addMonths(nowYm(), -24)) }
        var to by rememberSaveable { mutableIntStateOf(if (period.index == 3) period.toYm else nowYm()) }
        val now = nowYm()
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("기간 선택", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    YmStepper("시작", from) { v ->
                        val nf = minOf(v, now)
                        from = nf
                        if (to < nf) to = nf
                        if (to > addMonths(nf, 120)) to = addMonths(nf, 120)
                    }
                    YmStepper("종료", to) { v ->
                        val nt = minOf(v, now)
                        to = nt
                        if (from > nt) from = nt
                        if (from < addMonths(nt, -120)) from = addMonths(nt, -120)
                    }
                    Text("« ‹ › »  1년 / 1개월 단위로 움직입니다. 최대 10년까지 조회할 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { onChange(DartPeriod(3, from, to)); dialog = false }) { Text("적용") } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun YmStepper(label: String, ym: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton("«") { onChange(addMonths(ym, -12)) }
                StepButton("‹") { onChange(addMonths(ym, -1)) }
                Text(
                    "${ym / 100}.${String.format(Locale.US, "%02d", ym % 100)}",
                    Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                )
                StepButton("›") { onChange(addMonths(ym, 1)) }
                StepButton("»") { onChange(addMonths(ym, 12)) }
            }
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun rememberPeriod(key: String): Pair<DartPeriod, (DartPeriod) -> Unit> {
    var index by rememberSaveable(key) { mutableIntStateOf(0) }
    var from by rememberSaveable(key) { mutableIntStateOf(addMonths(nowYm(), -24)) }
    var to by rememberSaveable(key) { mutableIntStateOf(nowYm()) }
    return DartPeriod(index, from, to) to { p -> index = p.index; from = p.fromYm; to = p.toYm }
}

@Composable
private fun FilingsTab(corp: DartCorp, type: String, tag: String, empty: String, state: DartUiState, viewModel: DartViewModel) {
    val (period, setPeriod) = rememberPeriod("${corp.corp}|$type")
    val arg = DartPeriodArg(period.from, period.to)
    LaunchedEffect(corp.corp, type, arg) { viewModel.ensureFilings(corp.corp, type, arg) }
    PeriodBar(period, setPeriod)
    LoadBox(state.filings["${corp.corp}|$type|${arg.from}|${arg.to}"], onRetry = { viewModel.ensureFilings(corp.corp, type, arg, force = true) }) { f ->
        if (f.items.isEmpty()) CenterMessage(empty, modifier = Modifier.fillMaxWidth().height(180.dp))
        else {
            FilingList(f.items, tag)
            Text("출처: DART · ${dateText(arg.from)} ~ ${dateText(arg.to)} · 누르면 원문 공시가 열립니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OtherTab(corp: DartCorp, state: DartUiState, viewModel: DartViewModel) {
    FilingsTab(corp, "e", "기타", "기간 안에 기타공시가 없습니다.", state, viewModel)
}
