package io.github.hdlee73.financenewsradar.accounting

import io.github.hdlee73.financenewsradar.CrashLogger
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.accounting.data.SavedSim
import io.github.hdlee73.financenewsradar.accounting.data.SimCodec
import io.github.hdlee73.financenewsradar.accounting.data.SimStore
import io.github.hdlee73.financenewsradar.accounting.data.StockCandidate
import io.github.hdlee73.financenewsradar.accounting.data.StockRepository
import io.github.hdlee73.financenewsradar.accounting.data.TradingExport
import io.github.hdlee73.financenewsradar.accounting.data.XlsxWriter
import io.github.hdlee73.financenewsradar.accounting.domain.Candle
import io.github.hdlee73.financenewsradar.accounting.domain.Actor
import io.github.hdlee73.financenewsradar.accounting.domain.ActorKind
import io.github.hdlee73.financenewsradar.accounting.domain.Motive
import io.github.hdlee73.financenewsradar.accounting.domain.SimSettings
import io.github.hdlee73.financenewsradar.accounting.domain.defaultAssign
import io.github.hdlee73.financenewsradar.accounting.domain.defaultMotive
import io.github.hdlee73.financenewsradar.accounting.domain.StockParser
import io.github.hdlee73.financenewsradar.accounting.domain.StockSummary
import io.github.hdlee73.financenewsradar.accounting.domain.ViolationRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StockUi(
    val query: String = "",
    val busy: Boolean = false,
    val candidates: List<StockCandidate> = emptyList(),
    val chosen: StockCandidate? = null,
    val summary: StockSummary? = null,
    val candles: List<Candle> = emptyList(),
    /** 산정 기간 입력값(yyyy-MM-dd) */
    val from: String = defaultFrom(),
    val to: String = defaultTo(),
    val message: String? = null
) {
    companion object {
        fun defaultFrom(): String = "${LocalDate.now().year - 1}-01-01"
        fun defaultTo(): String = "${LocalDate.now().year - 1}-12-31"
    }
}

data class StoreUi(
    val saved: List<SavedSim> = emptyList(),
    /** 지금 화면이 불러온(또는 저장한) 시뮬레이션 id. 같은 이름으로 다시 저장하면 덮어쓴다. */
    val currentId: String? = null,
    val currentName: String = "",
    val message: String? = null
)

/** 화면 회전·이동에도 입력값이 유지되도록 시뮬레이터 상태를 ViewModel에 둔다. */
class SimulatorViewModel(app: Application) : AndroidViewModel(app) {
    private val stocks = StockRepository()
    private val store = SimStore(app)
    private val _settings = MutableStateFlow(SimSettings())
    val settings: StateFlow<SimSettings> = _settings.asStateFlow()
    private val _stock = MutableStateFlow(StockUi())
    val stock: StateFlow<StockUi> = _stock.asStateFlow()
    private val _store = MutableStateFlow(StoreUi())
    val storeUi: StateFlow<StoreUi> = _store.asStateFlow()
    private var nextId = 2
    private var job: Job? = null

    init {
        viewModelScope.launch { _store.update { it.copy(saved = store.list()) } }
    }

    fun update(block: (SimSettings) -> SimSettings) = _settings.update(block)

    fun addRow() { _settings.update { it.copy(rows = it.rows + ViolationRow(nextId++, assign = defaultAssign(it.actors))) } }
    fun removeRow(id: Int) { _settings.update { s -> if (s.rows.size <= 1) s else s.copy(rows = s.rows.filterNot { it.id == id }) } }
    fun updateRow(id: Int, f: (ViolationRow) -> ViolationRow) {
        _settings.update { s -> s.copy(rows = s.rows.map { if (it.id == id) f(it) else it }) }
    }

    // ── 주가·일평균거래금액

    // ── 양정 대상자(회사·임직원·감사인·공인회계사)

    fun addActor(kind: ActorKind) {
        _settings.update { s ->
            val id = (s.actors.maxOfOrNull { it.id } ?: 0) + 1
            val n = s.actors.count { it.kind == kind } + 1
            val actor = Actor(id, kind, "${kind.label} $n")
            s.copy(actors = s.actors + actor, rows = s.rows.map { it.copy(assign = it.assign + (id to defaultMotive(kind))) })
        }
    }

    /** 회사는 지울 수 없다. */
    fun removeActor(id: Int) {
        _settings.update { s ->
            val target = s.actors.firstOrNull { it.id == id } ?: return@update s
            if (target.kind == ActorKind.COMPANY) s
            else s.copy(actors = s.actors.filterNot { it.id == id }, rows = s.rows.map { it.copy(assign = it.assign - id) })
        }
    }

    fun updateActor(id: Int, f: (Actor) -> Actor) {
        _settings.update { s -> s.copy(actors = s.actors.map { if (it.id == id) f(it) else it }) }
    }

    /** motive가 null이면 그 위반행위는 그 대상자에게 해당하지 않음 */
    fun setAssign(rowId: Int, actorId: Int, motive: Motive?) {
        updateRow(rowId) { r -> r.copy(assign = if (motive == null) r.assign - actorId else r.assign + (actorId to motive)) }
    }

    fun setQuery(q: String) { _stock.update { it.copy(query = q) } }
    fun setFrom(v: String) { _stock.update { it.copy(from = v.take(10)) } }
    fun setTo(v: String) { _stock.update { it.copy(to = v.take(10)) } }

    fun search() {
        val q = _stock.value.query.trim()
        if (q.isEmpty()) { _stock.update { it.copy(message = "회사명을 입력하세요.") }; return }
        job?.cancel()
        _stock.update { it.copy(busy = true, message = null, candidates = emptyList(), summary = null, candles = emptyList(), chosen = null) }
        job = viewModelScope.launch {
            try {
                val list = stocks.search(q)
                when {
                    list.isEmpty() -> _stock.update { it.copy(busy = false, message = "상장 종목을 찾지 못했습니다. 비상장이면 아래 값을 직접 입력하세요.") }
                    list.size == 1 -> load(list[0])
                    else -> _stock.update { it.copy(busy = false, candidates = list) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "주가 검색 실패", e)
                _stock.update { it.copy(busy = false, message = "주가를 가져오지 못했습니다. 네트워크를 확인하세요. (${e.javaClass.simpleName})") }
            }
        }
    }

    fun choose(c: StockCandidate) {
        job?.cancel()
        job = viewModelScope.launch { load(c) }
    }

    /** 산정 기간을 바꾼 뒤 다시 계산 */
    fun recalc() {
        val c = _stock.value.chosen ?: run { search(); return }
        choose(c)
    }

    private suspend fun load(c: StockCandidate) {
        val st = _stock.value
        val from = StockParser.ymd(st.from)
        val to = StockParser.ymd(st.to)
        if (from == null || to == null) {
            _stock.update { it.copy(busy = false, chosen = c, candidates = emptyList(), query = c.name, message = "산정 기간을 yyyy-MM-dd 형식으로 입력하세요 (예: 2025-01-01).") }
            return
        }
        if (from > to) {
            _stock.update { it.copy(busy = false, chosen = c, candidates = emptyList(), query = c.name, message = "시작일이 종료일보다 늦습니다.") }
            return
        }
        if (LocalDate.parse(StockParser.dash(from)).plusYears(5) < LocalDate.parse(StockParser.dash(to))) {
            _stock.update { it.copy(busy = false, chosen = c, candidates = emptyList(), query = c.name, message = "산정 기간은 5년 이내로 입력하세요.") }
            return
        }
        _stock.update { it.copy(busy = true, message = null, candidates = emptyList(), chosen = c, query = c.name) }
        try {
            val (sum, days) = stocks.period(c, from, to)
            _stock.update {
                it.copy(busy = false, summary = sum, candles = days,
                    message = if (sum.avgTradingWon == null) "${sum.from} ~ ${sum.to} 기간의 거래 자료가 없습니다(상장 전·거래정지 등)." else null)
            }
            StockParser.kindFor(c.market)?.let { k -> _settings.update { it.copy(kind = k) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.log(getApplication(), "주가 조회 실패", e)
            _stock.update { it.copy(busy = false, message = "시세를 가져오지 못했습니다. (${e.javaClass.simpleName})") }
        }
    }

    /** 산정 내역 엑셀 파일 이름(저장 대화상자 기본값) */
    fun tradingFileName(): String? = _stock.value.summary?.let { TradingExport.fileName(it) }

    fun exportTrading(uri: Uri) {
        val st = _stock.value
        val sum = st.summary ?: return
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("파일을 열 수 없습니다")
                    out.use { XlsxWriter.write(it, TradingExport.sheets(sum, st.candles)) }
                }
                "산정 내역을 엑셀로 저장했습니다."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "거래금액 엑셀 저장 실패", e)
                "엑셀 저장에 실패했습니다. (${e.javaClass.simpleName})"
            }
            _stock.update { it.copy(message = msg) }
        }
    }

    // ── 저장·불러오기 (이 기기 안에만 저장)

    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    fun setSimName(n: String) { _store.update { it.copy(currentName = n.take(60)) } }

    private fun snapshot(id: String, name: String) = SavedSim(
        id = id, name = name, savedAt = now(), settings = _settings.value,
        company = _stock.value.query.trim(), stock = _stock.value.summary
    )

    /** 이름이 같은 현재 시뮬레이션이면 덮어쓰고, 이름을 바꾸면 새로 저장한다. */
    fun save() {
        val st = _store.value
        val name = st.currentName.trim().ifEmpty { _stock.value.query.trim().ifEmpty { "시뮬레이션" } + " " + now() }
        val existing = st.saved.firstOrNull { it.id == st.currentId && it.name == name }
        val id = existing?.id ?: System.currentTimeMillis().toString()
        viewModelScope.launch {
            try {
                val list = store.save(snapshot(id, name))
                _store.update { it.copy(saved = list, currentId = id, currentName = name, message = "‘$name’을(를) 이 기기에 저장했습니다.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "시뮬레이션 저장 실패", e)
                _store.update { it.copy(message = "저장에 실패했습니다. (${e.javaClass.simpleName})") }
            }
        }
    }

    fun open(sim: SavedSim) {
        job?.cancel()
        _settings.value = sim.settings
        nextId = (sim.settings.rows.maxOfOrNull { it.id } ?: 1) + 1
        val sm = sim.stock
        _stock.value = StockUi(
            query = sim.company.ifEmpty { sm?.name.orEmpty() },
            summary = sm,
            chosen = sm?.let { StockCandidate(it.code, it.name, it.market) },
            from = sm?.from?.takeIf { it.isNotEmpty() } ?: StockUi.defaultFrom(),
            to = sm?.to?.takeIf { it.isNotEmpty() } ?: StockUi.defaultTo(),
            message = if (sm != null) "저장 당시 산정값입니다. 일별 내역 엑셀이 필요하면 ‘다시 계산’을 누르세요." else null
        )
        _store.update { it.copy(currentId = sim.id, currentName = sim.name, message = "‘${sim.name}’을(를) 불러왔습니다.") }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                val list = store.delete(id)
                _store.update { it.copy(saved = list, currentId = if (it.currentId == id) null else it.currentId, message = "삭제했습니다.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "시뮬레이션 삭제 실패", e)
                _store.update { it.copy(message = "삭제에 실패했습니다.") }
            }
        }
    }

    /** 새 시뮬레이션: 입력을 모두 비운다(저장 목록은 그대로). */
    fun reset() {
        job?.cancel()
        _settings.value = SimSettings()
        nextId = 2
        _stock.value = StockUi()
        _store.update { it.copy(currentId = null, currentName = "", message = null) }
    }

    fun simFileName(): String {
        val n = _store.value.currentName.trim().ifEmpty { "양정시뮬레이션" }
        return "${n}.json".replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    /** 현재 시뮬레이션을 파일로 내보내기(다른 기기·웹 버전에서 가져올 수 있는 JSON) */
    fun exportFile(uri: Uri) {
        val name = _store.value.currentName.trim().ifEmpty { "시뮬레이션" }
        val sim = snapshot(_store.value.currentId ?: System.currentTimeMillis().toString(), name)
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("파일을 열 수 없습니다")
                    out.use { it.write(SimCodec.encodeList(listOf(sim)).toByteArray(Charsets.UTF_8)) }
                }
                "파일로 내보냈습니다."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "시뮬레이션 내보내기 실패", e)
                "내보내기에 실패했습니다. (${e.javaClass.simpleName})"
            }
            _store.update { it.copy(message = msg) }
        }
    }

    /** 파일 가져오기: 파일 안의 시뮬레이션을 이 기기 저장 목록에 추가하고 첫 건을 불러온다. */
    fun importFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다")
                    val text = input.use { s -> String(s.readBytes(limit = 2_000_000), Charsets.UTF_8) }
                    SimCodec.decodeList(text)
                }
                if (list.isEmpty()) { _store.update { it.copy(message = "시뮬레이션 파일이 아니거나 내용이 비어 있습니다.") }; return@launch }
                var saved = _store.value.saved
                list.forEach { saved = store.save(it) }
                _store.update { it.copy(saved = saved) }
                open(list.first())
                _store.update { it.copy(message = "${list.size}건을 가져와 이 기기에 저장했습니다.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger.log(getApplication(), "시뮬레이션 가져오기 실패", e)
                _store.update { it.copy(message = "가져오기에 실패했습니다. 이 앱(또는 웹 버전)에서 내보낸 파일인지 확인하세요.") }
            }
        }
    }

    fun clearStoreMessage() { _store.update { it.copy(message = null) } }
}

/** 입력 크기를 제한해 읽는다(잘못된 대용량 파일로 인한 메모리 문제 방지). */
private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        buf.write(chunk, 0, n)
        if (buf.size() > limit) error("파일이 너무 큽니다")
    }
    return buf.toByteArray()
}
