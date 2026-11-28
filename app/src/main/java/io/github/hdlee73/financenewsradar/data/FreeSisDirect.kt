package io.github.hdlee73.financenewsradar.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/**
 * 금융투자협회 FreeSIS '회사별설정규모'(펀드)를 기기에서 직접 받아 fund-aum.json과 같은 모양으로 만든다.
 * FreeSIS가 해외 IP(GitHub Actions 서버 등)를 막아 매일 자료 수집이 실패할 때, 한국 망에 있는 기기가 대신 받는다.
 * server/fund-data/build_fund_aum.py 와 같은 요청·계산이다(열 번호 TMPV14 합계, TMPV13 기관전용 사모펀드는 뺀다).
 */
object FreeSisDirect {
    private const val BASE = "https://freesis.kofia.or.kr/meta/"
    private const val REFERER = "https://freesis.kofia.or.kr/stat/FreeSIS.do?parentDivId=MSIS40200000000000&serviceId=STATFND0200100010"
    private const val UA = "Mozilla/5.0 (Linux; Android 13; SM-S911N) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 한 번의 요청. 응답이 비었거나 JSON이 아니면 null(차단·끊김). */
    fun interface Post {
        fun call(endpoint: String, body: JSONObject): JSONObject?
    }

    /** 실제 네트워크 요청. 응답이 가끔 끊겨 오므로 [tries]번까지 다시 받는다. 첫 시도부터 연결이 안 되면(해외 차단 등) 바로 포기한다. */
    fun networkPost(tries: Int = 3): Post = Post { endpoint, body ->
        var result: JSONObject? = null
        for (i in 0 until tries) {
            result = runCatching { once(endpoint, body) }.getOrNull()
            if (result != null) break
            Thread.sleep(1_000L * (i + 1))
        }
        result
    }

    private fun once(endpoint: String, body: JSONObject): JSONObject? {
        val c = URI(BASE + endpoint).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 25_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Referer", REFERER)
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) return null
            val raw = if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(c.inputStream) else c.inputStream
            val out = ByteArrayOutputStream()
            raw.use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            val text = out.toString(Charsets.UTF_8.name())
            return if (text.isBlank()) null else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** fund-aum.json 본문을 만든다. 자료를 못 받거나 너무 적으면 IllegalStateException. */
    fun build(post: Post, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.ofHours(9))): String {
        val dates = latestDates(post)
        val series = LinkedHashMap<String, Array<LongArray>>() // 이름 -> [공모[3], 사모[3]]
        val keys = listOf(dates.first, dates.second, dates.third)
        for (idx in keys.indices) {
            for (kind in 1..2) {
                val rows = fetch(post, keys[idx], kind)
                check(rows.size >= 30) { "FreeSIS ${keys[idx]} ${if (kind == 1) "공모" else "사모"} 자료가 너무 적습니다(${rows.size}곳)" }
                for ((name, v) in rows) {
                    series.getOrPut(name) { arrayOf(LongArray(3), LongArray(3)) }[kind - 1][idx] = v
                }
            }
        }
        val list = series.entries.filter { (_, s) -> s.any { a -> a.any { it != 0L } } }.sortedBy { it.key }
        check(list.size >= 100) { "FreeSIS 회사가 너무 적습니다(${list.size}곳)" }
        val companies = JSONArray()
        for ((name, s) in list) {
            companies.put(JSONObject().put("n", name).put("pub", JSONArray(s[0].toList())).put("pri", JSONArray(s[1].toList())))
        }
        return JSONObject()
            .put("generatedAt", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")))
            .put("unit", "억원")
            .put("asOf", JSONObject().put("recent", dates.first).put("quarter", dates.second).put("year", dates.third))
            .put("source", "금융투자협회 종합통계 포털 '회사별설정규모'(기기에서 직접 수집, 펀드 순자산총액, 기관전용 사모펀드 제외)")
            .put("companies", companies)
            .toString()
    }

    private fun latestDates(post: Post): Triple<String, String, String> {
        val body = JSONObject().put(
            "dmSearchData",
            JSONObject().put("strSvrId", "STATFND0200100130").put("strDivId", "MSIS40000000000000")
                .put("app_peron_yn", "Y").put("language_gb", "KOR").put("strGetCode", "Y")
        )
        val d = post.call("getSrvData.do", body) ?: error("FreeSIS에 연결하지 못했습니다.")
        val arr = d.optJSONArray("dsLatestDate") ?: error("FreeSIS 기준일을 받지 못했습니다.")
        val m = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            m[r.optString("TMPV1")] = r.optString("TMPV2")
        }
        val recent = m["RD"].orEmpty()
        val quarter = m["RQ"].orEmpty()
        val year = m["RY"].orEmpty()
        check(recent.isNotEmpty() && quarter.isNotEmpty() && year.isNotEmpty()) { "FreeSIS 기준일을 받지 못했습니다." }
        return Triple(recent, quarter, year)
    }

    /** kind: 1 공모, 2 사모. 반환: 회사명 -> 순자산총액(억원). */
    private fun fetch(post: Post, date: String, kind: Int): Map<String, Long> {
        val body = JSONObject().put(
            "dmSearch",
            JSONObject().put("tmpV40", "100000000").put("tmpV41", "1").put("tmpV34", date).put("tmpV6", "2")
                .put("tmpV4", "").put("tmpV5", "").put("tmpV7", kind.toString()).put("tmpV20", "Y")
                .put("OBJ_NM", "STATFND0200100010BO")
        )
        val d = post.call("getMetaDataList.do", body) ?: error("FreeSIS 응답을 받지 못했습니다($date).")
        val rows = d.optJSONArray("ds1") ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val name = r.optString("TMPV1").trim()
            if (name.isEmpty() || name == "계" || listOf("소계", "합계", "총계", "(기타)").any { name.contains(it) }) continue
            val total = r.optDouble("TMPV14", 0.0).let { if (it.isNaN()) 0.0 else it }
            val pef = r.optDouble("TMPV13", 0.0).let { if (it.isNaN()) 0.0 else it }
            out[name] = maxOf(total.toLong() - pef.toLong(), 0L)
        }
        return out
    }
}
