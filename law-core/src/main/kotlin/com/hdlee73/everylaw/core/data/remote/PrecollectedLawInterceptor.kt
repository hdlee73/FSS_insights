package com.hdlee73.everylaw.core.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 사전수집한 금융 관련 법령 응답(GitHub Actions가 매일 law-data 브랜치에 올림)을 먼저 쓰는 인터셉터.
 * 같은 요청(엔드포인트·대상·검색어·번호)이 있으면 서버 왕복 없이 저장된 XML을 돌려주고, 없거나 자료가 오래됐거나
 * 받는 데 실패하면 그냥 원래 요청을 보낸다(실시간 조회). 조문 번호(JO)만 다른 요청은 법령 전체 본문으로 대신한다.
 * 요청 키 규칙은 수집기 server/law-data/build_law.mjs의 keyOf()와 같다.
 */
class PrecollectedLawInterceptor(
    private val baseUrl: String = DEFAULT_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {

    private class Index(val keys: Map<String, String>, val loadedAt: Long, val usable: Boolean)

    @Volatile private var index: Index? = null
    private val files = ConcurrentHashMap<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val key = keyOf(req)
        if (key != null) {
            val xml = runCatching { lookup(key) }.getOrNull()
            if (xml != null) return synthetic(req, xml)
        }
        return chain.proceed(req)
    }

    private fun keyOf(req: Request): String? {
        if (req.method != "GET") return null
        val segs = req.url.pathSegments
        val endpoint = segs.lastOrNull() ?: return null
        if (endpoint != "lawSearch.do" && endpoint != "lawService.do") return null
        val params = req.url.queryParameterNames.filter { it != "type" && it != "JO" && it != "OC" }
            .mapNotNull { n -> req.url.queryParameter(n)?.let { "$n=$it" } }
            .sorted()
        return endpoint + "?" + params.joinToString("&")
    }

    private fun lookup(key: String): String? {
        val idx = loadIndex()
        if (!idx.usable) return null
        val name = idx.keys[key] ?: return null
        files[name]?.let { return it }
        val text = fetch(name) ?: return null
        if (files.values.sumOf { it.length } + text.length > MAX_CACHED_CHARS) files.clear() // 메모리 상한(큰 법령 몇 개분)
        files[name] = text
        return text
    }

    private fun loadIndex(): Index {
        index?.let { if (now() - it.loadedAt < (if (it.usable) INDEX_TTL_MS else RETRY_MS)) return it }
        synchronized(this) {
            index?.let { if (now() - it.loadedAt < (if (it.usable) INDEX_TTL_MS else RETRY_MS)) return it }
            val loaded = runCatching {
                val text = fetch("index.json") ?: error("없음")
                // org.json은 순수 JVM 모듈에 없어 가볍게 직접 읽는다(수집기가 만든 단순한 구조: {"updated":"…","keys":{"키":"파일", …}}).
                val updated = Regex(""""updated"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1).orEmpty()
                val body = text.substring(text.indexOf("\"keys\""))
                val keys = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*"([^"]*)"""").findAll(body.substringAfter('{'))
                    .associate { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") to it.groupValues[2] }
                Index(keys, now(), isFresh(updated, now()))
            }.getOrElse { Index(emptyMap(), now(), false) }
            if (loaded.keys.isEmpty() || !loaded.usable) files.clear()
            index = loaded
            return loaded
        }
    }

    private fun fetch(name: String): String? =
        client.newCall(Request.Builder().url(baseUrl + name).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private fun synthetic(req: Request, xml: String) = Response.Builder()
        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("X-Precollected", "1")
        .body(xml.toResponseBody("application/xml; charset=utf-8".toMediaType()))
        .build()

    companion object {
        const val DEFAULT_BASE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/law-data/"
        const val INDEX_TTL_MS = 6 * 3600_000L
        const val RETRY_MS = 10 * 60_000L
        private const val MAX_CACHED_CHARS = 12_000_000
        const val STALE_MS = 5 * 86_400_000L

        /** "2026-11-13 06:05 KST" 형식. 5일 넘게 갱신되지 않았거나 읽을 수 없으면 쓰지 않는다. */
        internal fun isFresh(updated: String, nowMs: Long): Boolean = runCatching {
            val m = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})""").find(updated) ?: return false
            val (y, mo, d, h, mi) = m.destructured
            val t = java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
            nowMs - t < STALE_MS
        }.getOrDefault(false)
    }
}
