package io.github.hdlee73.financenewsradar.law

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hdlee73.everylaw.core.domain.DelegationTreeBuilder
import com.hdlee73.everylaw.core.domain.HwpxExporter
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawQuery
import com.hdlee73.everylaw.core.model.LawSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 기기(APK) 안에서도 HWPX 템플릿 리소스가 읽혀 올바른 패키지가 만들어지는지. */
@RunWith(AndroidJUnit4::class)
class HwpxExportDeviceTest {
    @Test
    fun writesHwpxPackageOnDevice() {
        val law = Article(ArticleNo.of("34"), "건전경영", "제34조(건전경영) ① 첫째 항이다.\n② 둘째 항은 대통령령으로 정한다.")
        val decree = Article(ArticleNo.of("9"), "세부", "제9조(세부) 법 제34조제2항에 따라 정한다.")
        val root = DelegationTreeBuilder().build(
            DelegationTreeBuilder.Input("은행법", law, "은행법 시행령", listOf(decree), null, emptyList(), emptyList(), emptyList()),
        )
        val tree = DelegationTree(LawQuery("은행법 제34조", "은행법", ArticleNo.of("34")), LawSummary("1", "1", "은행법", null, "법률", null), root)
        for (compare in listOf(false, true)) {
            val out = ByteArrayOutputStream()
            HwpxExporter.write(tree, compare, setOf(LawLevel.DECREE), out)
            val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.also { z.closeEntry() } }.map { it.name }.toList() }
            assertEquals("mimetype", names.first())
            assertTrue(names.contains("Contents/header.xml") && names.contains("Contents/section0.xml"))
        }
    }
}
