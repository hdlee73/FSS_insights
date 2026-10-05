package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.UsefulLink
import org.junit.Assert.assertEquals
import org.junit.Test

class SiteSummaryTest {
    @Test
    fun readsMetaDescriptionThenTitle() {
        assertEquals("소개 &문구", SiteSummary.fromHtml("""<head><title>T</title><meta name="description" content="소개 &amp;문구"></head>"""))
        assertEquals("제목", SiteSummary.fromHtml("<title> 제목 </title>"))
        assertEquals("", SiteSummary.fromHtml("<p>x</p>"))
    }

    @Test
    fun defaultNoteMatchesMostSpecificHost() {
        assertEquals(true, UsefulLink.defaultNote("https://dart.fss.or.kr").contains("공시"))
        assertEquals(true, UsefulLink.defaultNote("https://www.fss.or.kr").contains("금융감독원"))
        assertEquals("", UsefulLink.defaultNote("https://example.com"))
    }
}
