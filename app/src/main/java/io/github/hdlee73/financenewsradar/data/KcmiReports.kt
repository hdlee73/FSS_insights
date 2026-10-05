package io.github.hdlee73.financenewsradar.data

import org.json.JSONArray
import java.time.LocalDate

/**
 * 자본시장연구원 보고서 목록. 목록 화면은 첫 4~5건만 HTML에 싣고 나머지는 '더보기'가
 * `json_report_list`(POST)로 불러오므로, 같은 주소를 직접 호출해 최근 N건을 받는다.
 */
internal object KcmiReports {
    const val BASE = "https://www.kcmi.re.kr"
    const val ENDPOINT = "$BASE/report/json_report_list"

    fun requestBody(page: Int, perPage: Int): String =
        "thispage=$page&perpage=$perPage&s_report_subject=&s_report_type="

    fun parse(json: String): List<ParsedLink> {
        val array = JSONArray(json.trim())
        val out = ArrayList<ParsedLink>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val title = o.optString("report_title").trim()
            val path = o.optString("report_pdf_preview_link").trim()
            if (title.isEmpty() || path.isEmpty()) continue
            val url = if (path.startsWith("http")) path else BASE + (if (path.startsWith("/")) "" else "/") + path
            out += ParsedLink(title, url, parseDate(o.optString("pub_date")))
        }
        return out
    }

    private fun parseDate(text: String): LocalDate? {
        val m = Regex("""(\d{4})\D(\d{1,2})\D(\d{1,2})""").find(text) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }
}
