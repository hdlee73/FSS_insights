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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.DartApi
import io.github.hdlee73.financenewsradar.data.DartCompany
import io.github.hdlee73.financenewsradar.data.DartCorp
import io.github.hdlee73.financenewsradar.data.DartFiling
import io.github.hdlee73.financenewsradar.data.DartFilings
import io.github.hdlee73.financenewsradar.data.DartHolders
import io.github.hdlee73.financenewsradar.data.DartStore
import io.github.hdlee73.financenewsradar.data.MarketClient
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
    /** 키는 "회사코드|a·f·e". */
    val filings: Map<String, DartLoad<DartFilings>> = emptyMap(),
    val kind: Map<String, DartLoad<DartFilings>> = emptyMap(),
    val stocks: Map<String, DartLoad<DartStock>> = emptyMap()
)

private const val MAX_WATCH = 12
private const val MAX_RECENT = 8

private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().time)
private fun daysAgo(n: Int): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)

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

    fun isWatched(corp: String) = _state.value.watch.any { it.corp == corp }

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
                    val full = corp.copy(name = c.name.ifBlank { corp.name }, stock = c.stock, market = c.market)
                    if (_state.value.watch.any { it.corp == corp.corp && it.market != c.market }) {
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

    fun ensureHolders(corp: String, force: Boolean = false) {
        if (!force && _state.value.holders[corp] is DartLoad.Done) return
        _state.update { it.copy(holders = it.holders + (corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { api.holders(corp) }
                .onSuccess { h -> _state.update { it.copy(holders = it.holders + (corp to DartLoad.Done(h))) } }
                .onFailure { e -> _state.update { it.copy(holders = it.holders + (corp to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    fun ensureFilings(corp: String, type: String, force: Boolean = false) {
        val key = "$corp|$type"
        if (!force && _state.value.filings[key] is DartLoad.Done) return
        _state.update { it.copy(filings = it.filings + (key to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { api.filings(corp, type) }
                .onSuccess { f -> _state.update { it.copy(filings = it.filings + (key to DartLoad.Done(f))) } }
                .onFailure { e -> _state.update { it.copy(filings = it.filings + (key to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    fun ensureKind(corp: DartCorp, force: Boolean = false) {
        if (corp.stock.isBlank()) return
        if (!force && _state.value.kind[corp.corp] is DartLoad.Done) return
        _state.update { it.copy(kind = it.kind + (corp.corp to DartLoad.Loading)) }
        viewModelScope.launch {
            runCatching { api.kind(corp.name) }
                .onSuccess { f -> _state.update { it.copy(kind = it.kind + (corp.corp to DartLoad.Done(f))) } }
                .onFailure { e -> _state.update { it.copy(kind = it.kind + (corp.corp to DartLoad.Failed(e.message ?: "불러오지 못했습니다."))) } }
        }
    }

    /** 이 회사의 가장 최근 공시일까지 봤다고 기록한다(새 공시 표시 해제). */
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
            val collected = runCatching {
                coroutineScope {
                    watch.map { corp ->
                        async {
                            val a = runCatching { api.filings(corp.corp, "a").items }.getOrDefault(emptyList())
                            val e = runCatching { api.filings(corp.corp, "e").items }.getOrDefault(emptyList())
                            (a + e).map { DartFeedItem(corp, it) }
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

private fun dateText(yyyymmdd: String): String =
    if (yyyymmdd.length == 8) "${yyyymmdd.substring(0, 4)}.${yyyymmdd.substring(4, 6)}.${yyyymmdd.substring(6, 8)}" else yyyymmdd

private fun grouped(value: Double, digits: Int = 0): String = String.format(Locale.US, "%,.${digits}f", value)

private fun signText(value: Double, digits: Int = 0): String = (if (value > 0) "▲" else if (value < 0) "▼" else "") + grouped(kotlin.math.abs(value), digits)

private fun dartChangeColor(change: Double?): Color = when {
    change == null || change == 0.0 -> Color.Unspecified
    change > 0 -> UpRed
    else -> DownBlue
}

private fun filingTag(f: DartFiling, fallback: String): String = when {
    f.src == "KIND" -> "KIND"
    else -> fallback
}

/** 기업현황 및 공시: 회사를 검색해 개황·최대주주·정기/감사/기타 공시와 주가를 보고, 관심기업을 모아 새 공시를 확인한다. */
@Composable
fun DartScreen(viewModel: DartViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.selected
    BackHandler(enabled = selected != null) { viewModel.back() }
    Column(modifier.fillMaxSize()) {
        if (selected == null) {
            LargeTitle("기업현황 및 공시", subtitle = "회사명·종목코드로 검색 · 관심기업의 새 공시 모아 보기")
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
                                InitialAvatar(corp.name, AppColors.heroEnd)
                                Column(Modifier.weight(1f)) {
                                    Text(corp.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(if (corp.stock.isBlank()) "비상장" else "${corp.stock} · 상장", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("DART(금융감독원 전자공시)와 KIND(한국거래소) 자료이며 주가는 지연될 수 있습니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val DART_TABS = listOf("개황", "최대주주", "정기보고서", "감사보고서", "기타공시")

@Composable
private fun DartDetail(corp: DartCorp, state: DartUiState, viewModel: DartViewModel) {
    LaunchedEffect(corp.corp) { viewModel.ensureCompany(corp) }
    var tab by rememberSaveable(corp.corp) { mutableIntStateOf(0) }
    val watched = state.watch.any { it.corp == corp.corp }
    val companyLoad = state.company[corp.corp]
    val company = (companyLoad as? DartLoad.Done)?.value
    val effective = if (company != null) corp.copy(name = company.name.ifBlank { corp.name }, stock = company.stock, market = company.market) else corp
    BackHeader(corp.name, onBack = viewModel::back) {
        IconButton(onClick = { viewModel.toggleWatch(effective) }) {
            Icon(if (watched) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = if (watched) "관심기업 해제" else "관심기업 추가")
        }
    }
    LaunchedEffect(tab, corp.corp) {
        when (tab) {
            1 -> viewModel.ensureHolders(corp.corp)
            2 -> viewModel.ensureFilings(corp.corp, "a")
            3 -> viewModel.ensureFilings(corp.corp, "f")
            4 -> {
                viewModel.ensureFilings(corp.corp, "e")
                viewModel.ensureKind(effective)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (effective.stock.isNotBlank()) StockCard(effective, state.stocks[corp.corp], onRetry = { viewModel.ensureStock(effective, force = true) })
        UnderlineTabs(DART_TABS, tab, { tab = it })
        when (tab) {
            0 -> OverviewTab(companyLoad, onRetry = { viewModel.ensureCompany(corp) })
            1 -> HoldersTab(state.holders[corp.corp], onRetry = { viewModel.ensureHolders(corp.corp, force = true) })
            2 -> FilingsTab(state.filings["${corp.corp}|a"], "정기", "최근 정기보고서(사업·반기·분기)가 없습니다.", onRetry = { viewModel.ensureFilings(corp.corp, "a", force = true) })
            3 -> FilingsTab(state.filings["${corp.corp}|f"], "감사", "최근 감사보고서가 없습니다.", onRetry = { viewModel.ensureFilings(corp.corp, "f", force = true) })
            else -> OtherTab(effective, state.filings["${corp.corp}|e"], state.kind[corp.corp], viewModel)
        }
    }
}

@Composable
private fun StockCard(corp: DartCorp, load: DartLoad<DartStock>?, onRetry: () -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(2) }
    val periods = listOf("1주" to 5, "1개월" to 21, "3개월" to 63, "1년" to 260)
    ListCard {
        Text("주가", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (load) {
            null, DartLoad.Loading -> Box(Modifier.fillMaxWidth().height(70.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            is DartLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시세를 불러오지 못했습니다.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("다시 시도") }
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
                val (label, count) = periods[period]
                val slice = s.closes.takeLast(count)
                if (slice.size >= 2) {
                    val up = slice.last() >= slice.first()
                    PriceLine(slice, if (up) UpRed else DownBlue, Modifier.fillMaxWidth().height(64.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        periods.forEachIndexed { i, (name, _) -> PillChip(name, onClick = { period = i }, selected = i == period, onPanel = true) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("52주 최저 ${grouped(s.low)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$label ${String.format(Locale.US, "%+.1f", (slice.last() / slice.first() - 1) * 100)}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("52주 최고 ${grouped(s.high)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("${corp.stock} · 시세 제공처 기준, 지연될 수 있음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            InfoLine("종목코드", c.stock.ifBlank { "비상장" })
            InfoLine("시장", c.marketName)
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
private fun HoldersTab(load: DartLoad<DartHolders>?, onRetry: () -> Unit) {
    LoadBox(load, onRetry) { h ->
        if (h.items.isEmpty()) {
            CenterMessage("최근 정기보고서에서 최대주주 현황을 찾지 못했습니다. 정기보고서 제출 대상이 아닌 회사일 수 있습니다.", modifier = Modifier.fillMaxWidth().height(180.dp))
            return@LoadBox
        }
        val total = h.items.sumOf { it.ratio ?: 0.0 }
        ListCard {
            Text("${h.basis} 기준", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("최대주주 및 특수관계인 합계 ${String.format(Locale.US, "%.2f", total)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        h.items.forEach { it ->
            ListCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(listOf(it.relation, it.kind).filter { s -> s.isNotBlank() && s != "-" }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(it.ratio?.let { r -> String.format(Locale.US, "%.2f%%", r) } ?: "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        it.shares?.let { s -> Text(grouped(s) + "주", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                val ratio = (it.ratio ?: 0.0).coerceIn(0.0, 100.0)
                Box(Modifier.fillMaxWidth().height(6.dp).clipBar(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((ratio / 100.0).toFloat().coerceAtLeast(0.01f)).height(6.dp).clipBar(AppColors.heroEnd))
                }
            }
        }
        Text("출처: DART 최대주주 현황(정기보고서). 특수관계인은 보고서에 기재된 범위입니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Modifier.clipBar(color: Color): Modifier = this
    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
    .background(color)

@Composable
private fun FilingList(items: List<DartFiling>, tagFallback: String) {
    val uri = LocalUriHandler.current
    items.forEach { f ->
        ListCard(onClick = { runCatching { uri.openUri(f.url) } }) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagLabel(filingTag(f, tagFallback), accent = f.src == "KIND")
                Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text(listOf(dateText(f.date), f.by, f.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilingsTab(load: DartLoad<DartFilings>?, tag: String, empty: String, onRetry: () -> Unit) {
    LoadBox(load, onRetry) { f ->
        if (f.items.isEmpty()) CenterMessage(empty, modifier = Modifier.fillMaxWidth().height(180.dp))
        else {
            FilingList(f.items, tag)
            Text("출처: DART · 누르면 원문 공시가 열립니다.", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OtherTab(corp: DartCorp, dart: DartLoad<DartFilings>?, kind: DartLoad<DartFilings>?, viewModel: DartViewModel) {
    var filter by rememberSaveable(corp.corp) { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("전체", "DART", "KIND").forEachIndexed { i, label -> PillChip(label, onClick = { filter = i }, selected = i == filter, onPanel = true) }
    }
    val dartItems = (dart as? DartLoad.Done)?.value?.items.orEmpty()
    val kindDone = (kind as? DartLoad.Done)?.value
    val kindItems = kindDone?.items.orEmpty()
    val merged = when (filter) {
        1 -> dartItems
        2 -> kindItems
        else -> (dartItems + kindItems).sortedByDescending { it.date }
    }
    val loading = (filter != 2 && (dart == null || dart == DartLoad.Loading)) || (filter != 1 && corp.stock.isNotBlank() && (kind == null || kind == DartLoad.Loading))
    if (loading && merged.isEmpty()) Box(Modifier.fillMaxWidth().height(120.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
    else if (merged.isEmpty()) {
        val err = (dart as? DartLoad.Failed)?.message ?: if (filter == 2) (kind as? DartLoad.Failed)?.message ?: kindDone?.error else null
        CenterMessage(err ?: if (filter == 2 && corp.stock.isBlank()) "비상장사는 KIND 공시가 없습니다." else "최근 기타공시가 없습니다.", isError = err != null, modifier = Modifier.fillMaxWidth().height(180.dp))
    } else FilingList(merged, "기타")
    val note = when {
        filter != 1 && corp.stock.isNotBlank() && ((kind as? DartLoad.Failed) != null || kindDone?.error != null) -> "KIND 공시를 읽지 못해 DART 공시만 보여 줍니다."
        filter != 1 && corp.stock.isBlank() -> "비상장사는 DART 공시만 있습니다."
        else -> "DART 주요사항·지분·기타·거래소공시와 KIND 공시 목록입니다."
    }
    Text(note, Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
