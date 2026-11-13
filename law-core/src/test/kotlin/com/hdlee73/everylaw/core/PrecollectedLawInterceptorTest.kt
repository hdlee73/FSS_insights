package com.hdlee73.everylaw.core

import com.hdlee73.everylaw.core.data.remote.PrecollectedLawInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecollectedLawInterceptorTest {
    private val data = MockWebServer()
    private val live = MockWebServer()

    private fun start(updated: String, withIndex: Boolean = true) {
        data.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/index.json" -> if (withIndex) MockResponse().setBody(
                    """{"updated":"$updated","keys":{"lawService.do?MST=1&target=law":"a.xml","lawSearch.do?display=20&query=은행법&search=1&target=law":"b.xml"}}""") else MockResponse().setResponseCode(404)
                "/a.xml" -> MockResponse().setBody("<법령>전체본문</법령>")
                "/b.xml" -> MockResponse().setBody("<LawSearch>검색</LawSearch>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        live.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("LIVE") }
        data.start(); live.start()
    }

    private fun client(): OkHttpClient {
        val base = data.url("/").toString()
        return OkHttpClient.Builder().addInterceptor(PrecollectedLawInterceptor(baseUrl = base)).build()
    }
    private fun get(c: OkHttpClient, q: String) =
        c.newCall(Request.Builder().url(live.url("/law/DRF/$q")).build()).execute().use { it.body!!.string() }

    private fun nowKst(minusMs: Long = 0): String {
        val t = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).minusNanos(minusMs * 1_000_000)
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " KST"
    }

    @Test fun `수집된 요청은 서버 없이, 조문 번호만 다른 본문 요청도 전체 본문으로`() {
        start(nowKst())
        val c = client()
        assertEquals("<LawSearch>검색</LawSearch>", get(c, "lawSearch.do?OC=x&target=law&type=XML&query=은행법&search=1&display=20"))
        assertEquals("<법령>전체본문</법령>", get(c, "lawService.do?OC=x&target=law&type=XML&MST=1&JO=000200"))
        assertEquals(0, live.requestCount)
    }

    @Test fun `없는 요청은 실시간 조회`() {
        start(nowKst())
        assertEquals("LIVE", get(client(), "lawSearch.do?target=law&type=XML&query=민법&search=1&display=20"))
        assertEquals(1, live.requestCount)
    }

    @Test fun `5일 넘게 갱신 안 된 자료와 자료 없음은 쓰지 않는다`() {
        start(nowKst(6 * 86_400_000L))
        assertEquals("LIVE", get(client(), "lawService.do?target=law&type=XML&MST=1"))
        data.shutdown(); live.shutdown()
        val d2 = MockWebServer(); d2.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404) }; d2.start()
        val c = OkHttpClient.Builder().addInterceptor(PrecollectedLawInterceptor(baseUrl = d2.url("/").toString())).build()
        val l2 = MockWebServer(); l2.enqueue(MockResponse().setBody("LIVE2")); l2.start()
        assertEquals("LIVE2", c.newCall(Request.Builder().url(l2.url("/law/DRF/lawService.do?target=law&MST=1")).build()).execute().use { it.body!!.string() })
        assertTrue(l2.requestCount == 1)
    }
}
