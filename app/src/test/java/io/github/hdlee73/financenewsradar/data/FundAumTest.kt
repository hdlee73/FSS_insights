package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FundAumTest {
    private val json = """
        {"generatedAt":"2026-11-07T07:30:00+09:00","unit":"억원",
         "asOf":{"recent":"20261105","quarter":"20260930","year":"20251231"},
         "companies":[
           {"n":"골든브릿지자산운용","pub":[86,98,146],"pri":[2187,2248,3292]},
           {"n":"교보자산운용","pub":[105356,111726,116710],"pri":[137862,0,0]},
           {"n":"미래에셋증권","pub":[10,20,30],"pri":[1,2,3]}
         ]}
    """.trimIndent()
    private val data = parseFundAum(json)

    @Test
    fun parsesThreeDatesAndPublicPrivate() {
        assertEquals("20260930", data.asOf.quarter)
        val c = data.find("골든브릿지자산운용")!!
        assertEquals(listOf(86L, 98L, 146L), c.pub)
        assertEquals(2273L, c.total(0))
    }

    @Test
    fun matchesCompanyNamesIgnoringCorporateFormAndSpaces() {
        assertEquals("교보자산운용", data.find("교보 자산운용(주)")?.name)
        assertEquals("미래에셋증권", data.find("미래에셋증권 주식회사")?.name)
    }

    @Test
    fun matchesWhenOnlyTheTrailingBusinessWordDiffers() {
        assertEquals("교보자산운용", data.find("교보운용")?.name)
    }

    @Test
    fun doesNotGuessUnknownCompanies() {
        assertNull(data.find("없는회사자산운용"))
        assertNull(data.find(""))
    }
}
