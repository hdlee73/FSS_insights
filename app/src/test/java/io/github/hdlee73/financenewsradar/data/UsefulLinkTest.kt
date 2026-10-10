package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.UsefulLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsefulLinkTest {
    @Test
    fun everyDefaultSiteHasDescription() {
        UsefulLink.DEFAULTS.forEach { assertTrue("${it.name} 설명 없음", it.description.isNotBlank()) }
    }

    @Test
    fun descriptionIsFoundByAddress() {
        assertTrue(UsefulLink.defaultDescription("https://dart.fss.or.kr/").contains("공시"))
        // dart.fss.or.kr 은 fss.or.kr 도 포함하지만 더 긴 주소가 먼저 맞아야 한다.
        assertEquals("", UsefulLink.defaultDescription("https://example.com"))
    }
}
