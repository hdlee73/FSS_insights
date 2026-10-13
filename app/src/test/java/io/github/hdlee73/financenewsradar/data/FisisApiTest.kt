package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FisisApiTest {
    @Test
    fun parsesCompaniesSortedByName() {
        val body = """{"items":[{"code":"0010002","name":"하나은행"},{"code":"0010001","name":"국민은행"},{"code":"","name":"x"}]}"""
        assertEquals(listOf("국민은행", "하나은행"), parseFisisCompanies(body).map { it.name })
    }

    @Test
    fun parsesProfile() {
        val body = """{"name":"국민은행","term":"Q","basisOptions":["별도"],"metrics":[
            {"key":"assets","label":"총자산","group":"fin","basis":"별도","unit":"백만원","term":"Q","column":"","source":"재무상태표 · 자산총계",
             "points":[{"month":"202506","value":500},{"month":"202509","value":520}]},
            {"key":"ceo","label":"대표이사","group":"info","basis":"","unit":"","term":"Q","column":"","source":"x",
             "points":[{"month":"202509","text":"홍길동"}]}]}"""
        val profile = parseFisisProfile(body)
        assertEquals(listOf("별도"), profile.basisOptions)
        val assets = profile.metric("assets", "별도")!!
        assertEquals(520.0, assets.at(null)!!.value!!, 1e-9)
        assertEquals(500.0, assets.at("202506")!!.value!!, 1e-9)
        assertNull(assets.at("202512"))
        assertEquals("홍길동", profile.metric("ceo")!!.at(null)!!.text)
        assertEquals(1, profile.group("info").size)
    }

    @Test
    fun parsesRankingPage() {
        val page = parseFisisRanking("""{"assets":{"0010001":{"value":500,"month":"202509","unit":"백만원"}},"next":30,"total":100,"ranked":true}""")
        assertEquals(30, page.next)
        assertEquals(500.0, page.assets.getValue("0010001").value, 1e-9)
        assertNull(parseFisisRanking("""{"assets":{},"next":null,"ranked":false}""").next)
    }

    @Test
    fun formatsAmountsInEokWon() {
        assertEquals("123,456억원", fisisValueText(12_345_600.0, "백만원"))
        assertEquals("30,000억원", fisisValueText(30_000.0, "억원"))
        assertEquals("6,039,261억원", fisisValueText(603_926_114_000_000.0, "원"))
        assertEquals("-120억원", fisisValueText(-12_000.0, "백만원"))
        assertEquals("15.46%", fisisValueText(15.46, "%"))
        assertEquals(5e11, fisisWon(500_000.0, "백만원"), 1.0)
    }

    @Test
    fun findsKnownHomepageOrSearchLink() {
        val known = fisisHomepage("주식회사 하나은행")
        assertEquals("https://www.kebhana.com", known.url)
        assertEquals(true, known.known)
        val unknown = fisisHomepage("가나다자산운용")
        assertEquals(false, unknown.known)
    }

    @Test
    fun formatsMonth() {
        assertEquals("2025.09", fisisMonthLabel("202509"))
        assertEquals("분기", fisisTermLabel("Q"))
    }
}
