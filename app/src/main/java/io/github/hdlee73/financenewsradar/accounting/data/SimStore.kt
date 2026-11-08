package io.github.hdlee73.financenewsradar.accounting.data

import android.content.Context
import io.github.hdlee73.financenewsradar.accounting.domain.Actor
import io.github.hdlee73.financenewsradar.accounting.domain.ActorKind
import io.github.hdlee73.financenewsradar.accounting.domain.COMPANY_ID
import io.github.hdlee73.financenewsradar.accounting.domain.CompanyKind
import io.github.hdlee73.financenewsradar.accounting.domain.defaultAssign
import io.github.hdlee73.financenewsradar.accounting.domain.Level
import io.github.hdlee73.financenewsradar.accounting.domain.Motive
import io.github.hdlee73.financenewsradar.accounting.domain.ScaleBasis
import io.github.hdlee73.financenewsradar.accounting.domain.SimSettings
import io.github.hdlee73.financenewsradar.accounting.domain.StockSummary
import io.github.hdlee73.financenewsradar.accounting.domain.VType
import io.github.hdlee73.financenewsradar.accounting.domain.ViolationRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 저장한 시뮬레이션 1건. 서버로 보내지 않고 이 기기에만 둔다. */
data class SavedSim(
    val id: String,
    val name: String,
    val savedAt: String,
    val settings: SimSettings,
    val company: String = "",
    val stock: StockSummary? = null
)

/** 시뮬레이션 저장소: 앱 내부 저장공간(filesDir/simulations.json)에만 쓴다. 파일 내보내기·가져오기도 같은 JSON 형식. */
class SimStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "simulations.json")
    private val lock = Mutex()

    suspend fun list(): List<SavedSim> = withContext(Dispatchers.IO) { lock.withLock { read() } }

    suspend fun save(sim: SavedSim): List<SavedSim> = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = listOf(sim) + read().filterNot { it.id == sim.id }
            write(all)
            all
        }
    }

    suspend fun delete(id: String): List<SavedSim> = withContext(Dispatchers.IO) {
        lock.withLock { read().filterNot { it.id == id }.also { write(it) } }
    }

    private fun read(): List<SavedSim> = runCatching { if (file.exists()) SimCodec.decodeList(file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun write(list: List<SavedSim>) {
        val tmp = File(file.parentFile, "simulations.json.tmp")
        tmp.writeText(SimCodec.encodeList(list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}

/** 저장 형식(JSON). 모르는 값은 기본값으로 읽어 이전·이후 버전과 호환한다. */
object SimCodec {
    const val FORMAT = "fss-accounting-simulation"

    fun encodeList(list: List<SavedSim>): String =
        JSONObject().put("format", FORMAT).put("version", 1).put("items", JSONArray(list.map { encode(it) })).toString()

    fun decodeList(text: String): List<SavedSim> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("items") ?: return listOfNotNull(runCatching { decode(root) }.getOrNull())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { decode(it) }.getOrNull() } }
    }

    fun encode(s: SavedSim): JSONObject = JSONObject()
        .put("id", s.id).put("name", s.name).put("savedAt", s.savedAt).put("company", s.company)
        .put("settings", settings(s.settings))
        .apply { s.stock?.let { put("stock", stock(it)) } }

    fun decode(o: JSONObject): SavedSim = SavedSim(
        id = o.optString("id").ifEmpty { System.currentTimeMillis().toString() },
        name = o.optString("name", "이름 없음"),
        savedAt = o.optString("savedAt"),
        settings = settings(o.optJSONObject("settings") ?: JSONObject()),
        company = o.optString("company"),
        stock = o.optJSONObject("stock")?.let { stock(it) }
    )

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun settings(s: SimSettings): JSONObject = JSONObject()
        .put("kind", s.kind.name).put("financial", s.financial).put("halfCoefficient", s.halfCoefficient)
        .put("assetEok", s.assetEok).put("salesEok", s.salesEok).put("materialityEok", s.materialityEok)
        .put("rows", JSONArray(s.rows.map { r ->
            JSONObject().put("id", r.id).put("type", r.type.name).put("basis", r.basis.name).put("amountEok", r.amountEok).put("title", r.title)
                .put("assign", JSONObject().also { o -> r.assign.forEach { (k, v) -> o.put(k.toString(), v.name) } })
        }))
        .put("actors", JSONArray(s.actors.map { a ->
            JSONObject().put("id", a.id).put("kind", a.kind.name).put("name", a.name).put("reasons", JSONArray(a.reasons.toList()))
                .put("content", a.content.name).put("benefitEok", a.benefitEok).put("feeEok", a.feeEok)
                .put("upFreePct", a.upFreePct).put("downFreePct", a.downFreePct)
        }))
        .put("marketImpact", s.marketImpact.name).put("years", s.years).put("capmEok", s.capmEok)

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun settings(o: JSONObject): SimSettings {
        val d = SimSettings()
        val kind = enumOf(o.optString("kind"), d.kind)
        val actors = if (o.has("actors")) actorsOf(o.optJSONArray("actors")) else legacyActors(o)
        val rowsArr = o.optJSONArray("rows")
        val rows = if (rowsArr == null) null else (0 until rowsArr.length()).mapNotNull { i ->
            val r = rowsArr.optJSONObject(i) ?: return@mapNotNull null
            val t = enumOf(r.optString("type"), VType.A)
            val assign: Map<Int, Motive> = if (r.has("assign")) {
                val ao = r.optJSONObject("assign") ?: JSONObject()
                ao.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { id -> enumValues<Motive>().firstOrNull { it.name == ao.optString(k) }?.let { id to it } } }.toMap()
            } else legacyAssign(o, r, actors)
            ViolationRow(
                id = r.optInt("id", i + 1), type = t, basis = enumOf(r.optString("basis"), t.defaultBasis),
                amountEok = r.optString("amountEok"), assign = assign, title = r.optString("title")
            )
        }?.ifEmpty { null }
        return SimSettings(
            kind = kind, financial = o.optBoolean("financial"), halfCoefficient = o.optBoolean("halfCoefficient"),
            assetEok = o.optString("assetEok"), salesEok = o.optString("salesEok"), materialityEok = o.optString("materialityEok"),
            rows = rows ?: listOf(ViolationRow(1, assign = defaultAssign(actors))), actors = actors,
            marketImpact = enumOf(o.optString("marketImpact"), d.marketImpact), years = o.optInt("years", 1).coerceIn(1, 10), capmEok = o.optString("capmEok")
        )
    }

    private fun actorsOf(arr: JSONArray?): List<Actor> {
        val list = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val k = enumOf(a.optString("kind"), ActorKind.OFFICER)
            Actor(
                id = a.optInt("id", i + 1), kind = k, name = a.optString("name"), reasons = a.optJSONArray("reasons").strings().toSet(),
                content = enumOf(a.optString("content"), if (k.companySide) Level.MID else Level.LOW),
                benefitEok = a.optString("benefitEok"), feeEok = a.optString("feeEok"),
                upFreePct = a.optInt("upFreePct"), downFreePct = a.optInt("downFreePct")
            )
        }.distinctBy { it.id }
        // 회사는 항상 한 곳 있어야 한다.
        return if (list.any { it.kind == ActorKind.COMPANY }) list else listOf(Actor(COMPANY_ID, ActorKind.COMPANY, "회사")) + list.filter { it.id != COMPANY_ID }
    }

    /** v0.3.0 이전 저장 파일(회사·임직원·감사인·공인회계사 한 묶음)을 대상자 목록으로 바꾼다. */
    private fun legacyActors(o: JSONObject): List<Actor> {
        val cReasons = o.optJSONArray("companyReasons").strings().toSet()
        val aReasons = o.optJSONArray("auditorReasons").strings().toSet()
        val up = o.optInt("upFreePct"); val down = o.optInt("downFreePct")
        val cContent = enumOf(o.optString("contentCompany"), Level.MID)
        val aContent = enumOf(o.optString("contentAuditor"), Level.LOW)
        val list = mutableListOf(
            Actor(COMPANY_ID, ActorKind.COMPANY, "회사", cReasons, cContent, upFreePct = up, downFreePct = down),
            Actor(4, ActorKind.OFFICER, "임직원 1", cReasons, cContent, benefitEok = o.optString("officerBenefitEok"), upFreePct = up, downFreePct = down)
        )
        if (o.optBoolean("auditorOn", true)) {
            list += Actor(2, ActorKind.AUDITOR, "감사인", aReasons, aContent, feeEok = o.optString("auditFeeEok"), upFreePct = up, downFreePct = down)
            list += Actor(3, ActorKind.CPA, "공인회계사 1", aReasons, aContent, upFreePct = up, downFreePct = down)
        }
        return list
    }

    private fun legacyAssign(o: JSONObject, r: JSONObject, actors: List<Actor>): Map<Int, Motive> {
        val cm = enumOf(r.optString("motive"), Motive.INTENT)
        val am = enumOf(o.optString("auditorMotive"), Motive.GROSS_NEGLIGENCE)
        return actors.associate { it.id to if (it.kind.companySide) cm else am }
    }

    private fun stock(s: StockSummary): JSONObject = JSONObject()
        .put("code", s.code).put("name", s.name).put("market", s.market).put("lastClose", s.lastClose ?: JSONObject.NULL)
        .put("lastDate", s.lastDate ?: JSONObject.NULL).put("avgTradingWon", s.avgTradingWon ?: JSONObject.NULL)
        .put("tradingDays", s.tradingDays).put("from", s.from).put("to", s.to).put("sumTradingWon", s.sumTradingWon)

    private fun stock(o: JSONObject): StockSummary = StockSummary(
        code = o.optString("code"), name = o.optString("name"), market = o.optString("market"),
        lastClose = if (o.isNull("lastClose")) null else o.optLong("lastClose"),
        lastDate = if (o.isNull("lastDate")) null else o.optString("lastDate"),
        avgTradingWon = if (o.isNull("avgTradingWon")) null else o.optLong("avgTradingWon"),
        tradingDays = o.optInt("tradingDays"), from = o.optString("from"), to = o.optString("to"), sumTradingWon = o.optLong("sumTradingWon")
    )
}
