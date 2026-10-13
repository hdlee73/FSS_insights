package io.github.hdlee73.financenewsradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** 자본시장 통계 한 줄(예: 공모펀드 순자산총액). [change]·[changePercent]는 직전 대비. */
data class KofiaItem(
    val name: String,
    val value: String,
    val change: Double?,
    val changeText: String,
    val changePercent: Double?
)

/** 소제목 하나(예: 펀드시장규모). [asOf]는 기준일("26/10/12"), [unit]은 원 자료의 단위. */
data class KofiaSection(val title: String, val asOf: String, val unit: String, val items: List<KofiaItem>)

/** 큰 구분(펀드, 자금동향). */
data class KofiaGroup(val title: String, val sections: List<KofiaSection>)

/** 화면에 보일 구분과, 금융투자협회 응답에서 읽을 목록 이름(응답 순서대로 소제목이 나온다). */
private val KOFIA_LISTS = mapOf(
    "펀드" to listOf("fundList1", "fundList2", "fundList3", "fundList4", "fundList5"),
    "자금동향" to listOf("fundTrendList1", "fundTrendList2", "fundTrendList3")
)

private fun JSONObject.num(key: String): Double? =
    opt(key).let { v -> if (v is Number) v.toDouble() else (v as? String)?.replace(",", "")?.trim()?.toDoubleOrNull() }

/**
 * 금융투자협회 종합통계 포털 "한눈에 보는 자본시장통계" 응답에서 펀드·자금동향 영역만 뽑는다.
 * 각 행의 TMPV14는 "구분>소제목>이름", TMPV3 기준일, TMPV4 값, TMPV5 단위, TMPV6 증감, TMPV7 등락률.
 */
internal fun parseKofia(body: String): List<KofiaGroup> {
    val root = JSONObject(body)
    return KOFIA_LISTS.mapNotNull { (group, listNames) ->
        val sections = LinkedHashMap<String, MutableList<KofiaItem>>()
        val meta = HashMap<String, Pair<String, String>>()
        for (listName in listNames) {
            val rows: JSONArray = root.optJSONArray(listName) ?: continue
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val parts = r.optString("TMPV14").split(">")
                if (parts.size < 3) continue
                val value = r.optString("TMPV4").trim()
                if (value.isEmpty()) continue
                val title = parts[1].trim()
                val change = r.num("TMPV6")
                val item = KofiaItem(
                    name = parts.drop(2).joinToString(">").replace(Regex("\\s*\\n\\s*"), " ").trim(),
                    value = value,
                    change = change,
                    changeText = r.optString("TMPV6").trim().ifEmpty { "0" },
                    changePercent = r.num("TMPV7")
                )
                sections.getOrPut(title) { mutableListOf() } += item
                meta.putIfAbsent(title, r.optString("TMPV3").trim() to r.optString("TMPV5").trim())
            }
        }
        if (sections.isEmpty()) null
        else KofiaGroup(group, sections.map { (t, items) -> KofiaSection(t, meta[t]?.first.orEmpty(), meta[t]?.second.orEmpty(), items) })
    }
}

/** 금융투자협회 종합통계 포털(freesis.kofia.or.kr)의 공개 통계를 앱에서 바로 받아 온다(열쇠 불필요). */
class KofiaApi {
    suspend fun load(): List<KofiaGroup> = withContext(Dispatchers.IO) {
        val c = URI("https://freesis.kofia.or.kr/alootStatsAll/STATTOT0600000900BO.do").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 25_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.setRequestProperty("Referer", "https://freesis.kofia.or.kr/stat/FreeSIS.do?parentDivId=MSIS02000000000000&serviceId=MSIS02010000000000")
        try {
            c.outputStream.use { it.write("""{"data":{"userId":"GUEST","searchLog":"메인","serviceId":"STATTOT0600000900"}}""".toByteArray()) }
            if (c.responseCode != 200) error("금융투자협회 응답 ${c.responseCode}")
            val groups = parseKofia(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            if (groups.isEmpty()) error("금융투자협회 응답에 자료가 없습니다")
            groups
        } finally {
            c.disconnect()
        }
    }
}
