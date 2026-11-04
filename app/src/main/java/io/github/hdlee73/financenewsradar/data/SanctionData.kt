package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.YearMonth

/**
 * 금융회사 제재공시 세부내역(2011년 이후).
 * 금감원 공시의 첨부 문서(PDF·HWP)는 앱에서 직접 읽기에 무거워, GitHub Actions가 주기적으로 모아
 * 지적사항 단위로 정리한 sanctions.json을 저장소의 sanction-data 브랜치에 올려 두고 앱은 그것만 받는다.
 */
object SanctionSectors {
    /** 권역(사용자 지정 순서). */
    val all = listOf("은행", "보험", "증권", "운용", "자문일임", "저축은행", "상호금융", "여신전문", "대부", "기타")
}

data class SanctionItem(
    /** 지적제목. */
    val title: String,
    /** 문책·주의·개선·경영유의·자율처리 등(원문의 묶음 제목에서 뽑음, 없으면 null). */
    val category: String?,
    /** "보험업법 제95조의2" 같은 위반 법률·조항. */
    val laws: List<String>
)

data class SanctionCase(
    val id: String,
    val company: String,
    val date: LocalDate,
    val dept: String,
    val sector: String,
    val viewUrl: String,
    val fileUrl: String?,
    /** 기관·임원·직원 조치(원문 표기). */
    val inst: String,
    val exec: String,
    val staff: String,
    val instKinds: List<String>,
    val execKinds: List<String>,
    val staffKinds: List<String>,
    /** 과태료·과징금(원, 건 전체). */
    val fine: Long,
    val penalty: Long,
    val items: List<SanctionItem>,
    /** 지적사항을 원문에서 뽑아냈는지. false면 원문을 직접 봐야 한다. */
    val parsed: Boolean
) {
    val money: Long get() = fine + penalty
    val year: Int get() = date.year

    /** 신분·금전 제재를 한 줄로(목록·엑셀용). */
    fun actionSummary(): String = listOfNotNull(
        inst.takeIf { it.isNotBlank() }?.let { "기관: $it" },
        exec.takeIf { it.isNotBlank() }?.let { "임원: $it" },
        staff.takeIf { it.isNotBlank() }?.let { "직원: $it" }
    ).joinToString(" | ")
}

/** 세부내역 목록의 한 줄 = 지적사항 1건(지적사항을 못 뽑은 공시는 공시 1건이 한 줄). */
data class SanctionRow(val case: SanctionCase, val item: SanctionItem?, val index: Int) {
    val title: String get() = item?.title ?: "지적사항 미분류 · 원문 참조"
    val laws: List<String> get() = item?.laws.orEmpty()
}

data class SanctionDataset(val generatedAt: String, val fromYear: Int, val cases: List<SanctionCase>) {
    val years: List<Int> by lazy { cases.map { it.year }.distinct().sortedDescending() }
}

/** 조치수준 거르기. */
enum class SanctionAction(val label: String) {
    ALL("조치 전체"),
    MONEY("금전제재(과태료·과징금)"),
    SUSPEND("영업·업무정지·인허가취소"),
    INST_WARNING("기관경고"),
    INST_CAUTION("기관주의"),
    EXEC_HEAVY("임원 중징계(해임권고·직무정지·문책경고)"),
    EXEC_LIGHT("임원 주의적경고·주의"),
    STAFF_HEAVY("직원 중징계(면직·정직·감봉)"),
    STAFF_LIGHT("직원 견책·주의");

    fun matches(c: SanctionCase): Boolean = when (this) {
        ALL -> true
        MONEY -> c.money > 0 || c.instKinds.any { it == "과태료" || it == "과징금" }
        SUSPEND -> c.instKinds.any { it in setOf("영업정지", "업무정지", "인가취소", "등록취소", "영업점폐쇄", "영업의 일부정지") }
        INST_WARNING -> "기관경고" in c.instKinds
        INST_CAUTION -> "기관주의" in c.instKinds
        EXEC_HEAVY -> c.execKinds.any { it in setOf("해임권고", "직무정지", "업무집행정지", "문책경고") }
        EXEC_LIGHT -> c.execKinds.any { it == "주의적경고" || it == "주의" }
        STAFF_HEAVY -> c.staffKinds.any { it in setOf("면직", "정직", "감봉") }
        STAFF_LIGHT -> c.staffKinds.any { it == "견책" || it == "주의" }
    }
}

/** 기간 빠른 선택. */
enum class SanctionPeriod(val label: String, val months: Long?) {
    ALL("전체", null), THIS_YEAR("올해", null), LAST_1Y("최근 1년", 12), LAST_3Y("최근 3년", 36), LAST_5Y("최근 5년", 60);

    fun range(today: LocalDate = LocalDate.now()): Pair<YearMonth?, YearMonth?> = when (this) {
        ALL -> null to null
        THIS_YEAR -> YearMonth.of(today.year, 1) to YearMonth.from(today)
        else -> YearMonth.from(today).minusMonths(months!! - 1) to YearMonth.from(today)
    }
}

data class SanctionFilter(
    /** 기간(조치요구일 기준, 시작 월 1일 ~ 끝 월 말일). null이면 제한 없음. */
    val from: YearMonth? = null,
    val to: YearMonth? = null,
    val sector: String? = null,
    val action: SanctionAction = SanctionAction.ALL,
    val query: String = ""
) {
    fun inPeriod(d: LocalDate): Boolean =
        (from == null || !d.isBefore(from.atDay(1))) && (to == null || !d.isAfter(to.atEndOfMonth()))

    fun matchesCase(c: SanctionCase): Boolean =
        inPeriod(c.date) && (sector == null || c.sector == sector) && action.matches(c)

    fun withYear(y: Int) = copy(from = YearMonth.of(y, 1), to = YearMonth.of(y, 12))
    fun withPeriod(p: SanctionPeriod): SanctionFilter = p.range().let { (f, t) -> copy(from = f, to = t) }

    /** 지금 기간이 빠른 선택 중 어느 것과 같은지(직접 지정이면 null). */
    fun preset(): SanctionPeriod? = SanctionPeriod.entries.firstOrNull { it.range() == (from to to) }

    /** "2019.01~2021.12", "2023년", "2011년 이후 전체" 같은 기간 표기. */
    fun periodLabel(fromYear: Int): String {
        val f = from; val t = to
        return when {
            f == null && t == null -> "${fromYear}년 이후 전체"
            f != null && t != null && f.year == t.year && f.monthValue == 1 && t.monthValue == 12 -> "${f.year}년"
            else -> "${f?.let { "%d.%02d".format(it.year, it.monthValue) } ?: "$fromYear.01"}~${t?.let { "%d.%02d".format(it.year, it.monthValue) } ?: "현재"}"
        }
    }

    fun matchesRow(r: SanctionRow): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val compact = q.replace(" ", "")
        val hay = listOf(r.case.company, r.title, r.case.dept, r.case.sector, r.laws.joinToString(" "), r.item?.category.orEmpty())
        return q.split(Regex("\\s+")).all { word -> hay.any { it.contains(word, ignoreCase = true) } } ||
            hay.any { it.replace(" ", "").contains(compact, ignoreCase = true) }
    }
}

object SanctionStats {
    fun rows(cases: List<SanctionCase>): List<SanctionRow> = cases.flatMap { c ->
        if (c.items.isEmpty()) listOf(SanctionRow(c, null, 0)) else c.items.mapIndexed { i, it -> SanctionRow(c, it, i) }
    }

    fun filter(data: SanctionDataset, f: SanctionFilter): List<SanctionRow> {
        val cases = data.cases.filter(f::matchesCase)
        return rows(cases).filter(f::matchesRow)
    }

    data class Summary(
        val caseCount: Int,
        val itemCount: Int,
        val fine: Long,
        val penalty: Long,
        val byYear: List<Pair<Int, Int>>,
        /** 월별 건수(기간 안의 모든 달, 0건 포함). */
        val byMonth: List<Pair<YearMonth, Int>>,
        val bySector: List<Pair<String, Int>>,
        val instKinds: List<Pair<String, Int>>,
        val execKinds: List<Pair<String, Int>>,
        val staffKinds: List<Pair<String, Int>>,
        val topLaws: List<Pair<String, Int>>,
        val topLawNames: List<Pair<String, Int>>,
        val topCompanies: List<Pair<String, Int>>,
        val topMoney: List<SanctionCase>
    )

    /** 거른 지적사항 [rows]로 통계를 낸다. 금액·조치 종류는 공시(건) 단위로 중복 없이 센다. */
    fun summarize(rows: List<SanctionRow>, allYears: List<Int>, filter: SanctionFilter = SanctionFilter()): Summary {
        val cases = rows.map { it.case }.distinctBy { it.id }
        val months = run {
            val dates = cases.map { YearMonth.from(it.date) }
            val start = filter.from ?: dates.minOrNull()
            val end = filter.to ?: dates.maxOrNull()
            if (start == null || end == null || start.isAfter(end)) emptyList()
            else generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }.take(240).toList()
        }
        val perMonth = cases.groupingBy { YearMonth.from(it.date) }.eachCount()
        val years = allYears.filter { y -> (filter.from == null || y >= filter.from.year) && (filter.to == null || y <= filter.to.year) }
        fun <T> count(xs: List<T>) = xs.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        val laws = rows.flatMap { it.laws.distinct() }
        return Summary(
            caseCount = cases.size,
            itemCount = rows.count { it.item != null },
            fine = cases.sumOf { it.fine },
            penalty = cases.sumOf { it.penalty },
            byYear = years.sorted().map { y -> y to cases.count { it.year == y } },
            byMonth = months.map { it to (perMonth[it] ?: 0) },
            bySector = SanctionSectors.all.map { s -> s to cases.count { it.sector == s } }.filter { it.second > 0 },
            instKinds = count(cases.flatMap { it.instKinds }),
            execKinds = count(cases.flatMap { it.execKinds }),
            staffKinds = count(cases.flatMap { it.staffKinds }),
            topLaws = count(laws).take(20),
            topLawNames = count(laws.map { it.substringBeforeLast(" 제") }).take(10),
            topCompanies = count(cases.map { it.company }).take(10),
            topMoney = cases.filter { it.money > 0 }.sortedByDescending { it.money }.take(10)
        )
    }

    /** 원 → "17억 3,050만원", "39백만원" 같은 읽기 쉬운 금액. */
    fun won(v: Long): String {
        if (v <= 0) return "-"
        val eok = v / 100_000_000
        val man = (v % 100_000_000) / 10_000
        return when {
            eok > 0 && man > 0 -> "%,d억 %,d만원".format(eok, man)
            eok > 0 -> "%,d억원".format(eok)
            man > 0 -> "%,d만원".format(man)
            else -> "%,d원".format(v)
        }
    }
}

object SanctionJson {
    fun parse(text: String): SanctionDataset {
        val o = JSONObject(text)
        val arr = o.getJSONArray("cases")
        val cases = ArrayList<SanctionCase>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val date = runCatching { LocalDate.parse(c.getString("date")) }.getOrNull() ?: continue
            val items = c.optJSONArray("items") ?: JSONArray()
            cases += SanctionCase(
                id = c.getString("id"),
                company = c.optString("co"),
                date = date,
                dept = c.optString("dept"),
                sector = c.optString("sector").ifBlank { "기타" },
                viewUrl = c.optString("view"),
                fileUrl = c.optString("file").takeIf { it.isNotBlank() && it != "null" },
                inst = c.optString("inst"),
                exec = c.optString("exec"),
                staff = c.optString("staff"),
                instKinds = strings(c.optJSONArray("instK")),
                execKinds = strings(c.optJSONArray("execK")),
                staffKinds = strings(c.optJSONArray("staffK")),
                fine = c.optLong("fine"),
                penalty = c.optLong("penalty"),
                items = (0 until items.length()).map { j ->
                    val it = items.getJSONObject(j)
                    SanctionItem(it.optString("t"), it.optString("cat").takeIf { s -> s.isNotBlank() && s != "null" }, strings(it.optJSONArray("laws")))
                },
                parsed = c.optBoolean("ok")
            )
        }
        return SanctionDataset(o.optString("generatedAt"), o.optInt("from", 2011), cases)
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}

/** sanctions.json을 받아 기기에 보관한다. 12시간 안에 받은 것이 있으면 다시 받지 않는다. */
class SanctionRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "sanctions.json")

    suspend fun cached(): SanctionDataset? = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) SanctionJson.parse(file.readText()) else null }.getOrNull()
    }

    suspend fun load(force: Boolean = false): SanctionDataset = withContext(Dispatchers.IO) {
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_MS
        if (!force && fresh) runCatching { return@withContext SanctionJson.parse(file.readText()) }
        val text = try {
            download(DATA_URL)
        } catch (e: Throwable) {
            if (file.exists()) return@withContext SanctionJson.parse(file.readText())
            throw IllegalStateException("제재공시 세부내역 자료를 받지 못했습니다. 인터넷 연결을 확인해 주세요. (${e.message})")
        }
        val data = SanctionJson.parse(text)
        File(context.filesDir, "sanctions.json.tmp").apply { writeText(text); renameTo(file) }
        data
    }

    private fun download(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code !in 200..299) error("서버 응답 $code")
            val stream = if ("gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val DATA_URL = "https://raw.githubusercontent.com/hdlee73/FSS_insights/sanction-data/sanctions.json"
        private const val REFRESH_MS = 12L * 60 * 60 * 1000
    }
}
