package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FisisApiTest {
    @Test
    fun parsesCompaniesSortedByName() {
        val body = """{"items":[{"code":"0010002","name":"하나은행"},{"code":"0010001","name":"국민은행"},{"code":"","name":"x"}]}"""
        assertEquals(listOf("국민은행", "하나은행"), parseFisisCompanies(body).map { it.name })
    }

    @Test
    fun parsesDataWithMissingValues() {
        val body = """{"term":"Q","name":"국민은행","columns":[{"id":"a","name":"금액"}],
            "accounts":[{"code":"A1","name":"BIS 자기자본비율","points":[
              {"month":"202506","v":{"a":15.0}},{"month":"202509","v":{"a":15.5}},{"month":"202512","v":{"a":null}}]}]}"""
        val data = parseFisisData(body)
        val account = data.accounts.single()
        assertEquals("Q", data.term)
        assertTrue(account.isRatio)
        assertEquals("202509", account.latest("a")?.month)
        assertEquals(0.5, account.change("a")!!, 1e-9)
        assertNull(account.points.last().values["a"])
    }

    @Test
    fun emptyResultIsEmpty() {
        assertTrue(parseFisisData("""{"term":"","name":"","columns":[],"accounts":[]}""").isEmpty)
    }

    @Test
    fun formatsMonthsAndValues() {
        assertEquals("2025.09", fisisMonthLabel("202509"))
        assertEquals("1,234,567", fisisValueText(1234567.0, false))
        assertEquals("15.50%", fisisValueText(15.5, true))
    }
}
