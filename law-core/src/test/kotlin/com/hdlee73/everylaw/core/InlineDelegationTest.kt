package com.hdlee73.everylaw.core

import com.hdlee73.everylaw.core.domain.CompareTable
import com.hdlee73.everylaw.core.domain.DelegationTreeBuilder
import com.hdlee73.everylaw.core.domain.ItemAttachment
import com.hdlee73.everylaw.core.domain.selectForPath
import com.hdlee73.everylaw.core.domain.stableKey
import com.hdlee73.everylaw.core.domain.withParagraphPieces
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDelegationTest {
    private val law = Article(
        ArticleNo.of("85"), "불건전 영업행위의 금지",
        "제85조(불건전 영업행위의 금지) 집합투자업자는 다음 각 호의 행위를 하여서는 아니 된다. 다만, 대통령령으로 정하는 경우에는 이를 할 수 있다.\n" +
            "1. 가격에 영향을 미치는 매매\n2. 자기 또는 대통령령으로 정하는 관계인수인이 인수한 증권을 매수하는 행위",
    )
    private val decree = Article(
        ArticleNo.of("87"), "불건전 영업행위의 금지",
        "제87조(불건전 영업행위의 금지) ① 법 제85조 각 호 외의 부분 단서에서 \"대통령령으로 정하는 경우\"란 다음 각 호의 경우를 말한다.\n" +
            "1. 첫째 경우\n② 법 제85조제2호에서 \"대통령령으로 정하는 관계인수인\"이란 계열회사를 말한다.",
    )
    private val rule = Article(ArticleNo.of("7"), "세부", "제7조(세부) 영 제87조제2항에 따른 관계인수인의 확인 방법은 다음과 같다.")

    private fun tree() = DelegationTreeBuilder().build(
        DelegationTreeBuilder.Input("자본시장법", law, "자본시장법 시행령", listOf(decree), "자본시장법 시행규칙", listOf(rule), emptyList(), emptyList()),
    )

    @Test fun leadCitationIsAnchoredToLead() {
        assertEquals(ItemPath.LEAD, DelegationTreeBuilder.anchorOf("법 제85조 각 호 외의 부분 단서에서", DelegationTreeBuilder.LAW_PREFIX, ArticleNo.of("85")))
        assertEquals(ItemPath(), DelegationTreeBuilder.anchorOf("법 제85조에 따라", DelegationTreeBuilder.LAW_PREFIX, ArticleNo.of("85")))
    }

    @Test fun decreeIsSplitPerParagraphAndAttachedInPlace() {
        val split = tree().withParagraphPieces()
        assertEquals(listOf("제1항", "제2항"), split.children.map { it.part })
        assertEquals(listOf(ItemPath.LEAD, ItemPath(subparagraph = "2")), split.children.map { it.anchor })
        // 시행규칙은 영 제87조제2항을 인용했으니 제2항 조각 밑
        assertTrue(split.children[0].children.isEmpty())
        assertEquals(1, split.children[1].children.size)
        val attached = ItemAttachment.attach(split.article, split.children)
        assertEquals(setOf(ItemPath.LEAD, ItemPath(subparagraph = "2")), attached.keys)
        assertNotEquals(split.children[0].stableKey, split.children[1].stableKey)
    }

    @Test fun itemQueryFindsDecreeParagraphAfterSplit() {
        val (kept, note) = selectForPath(tree().withParagraphPieces().children, ItemPath(subparagraph = "2"))
        assertEquals(listOf("제2항"), kept.map { it.part })
        assertEquals(null, note)
    }

    @Test fun itemQueryWithoutDirectCitationFallsBackToWholeArticle() {
        val (kept, note) = selectForPath(tree().withParagraphPieces().children, ItemPath(subparagraph = "1"))
        assertEquals(2, kept.size)
        assertTrue(note != null)
    }

    @Test fun singleLocationIsNotSplit() {
        val d = decree.copy(content = "제87조(x) ① 법 제85조제2호에 따라 가.\n② 법 제85조제2호에 따라 나.")
        val t = DelegationTreeBuilder().build(
            DelegationTreeBuilder.Input("자본시장법", law, "령", listOf(d), null, emptyList(), emptyList(), emptyList()),
        )
        assertEquals(1, t.withParagraphPieces().children.size)
    }

    @Test fun compareRowsAlignLawPartsWithDelegations() {
        val rows = CompareTable.build(tree(), setOf(LawLevel.DECREE, LawLevel.RULE))
        assertEquals(2, rows.size)
        assertTrue(rows[0].lawText.startsWith("제85조(불건전 영업행위의 금지)"))
        assertTrue(rows[0].lawText.contains("다만"))
        assertEquals(listOf(LawLevel.DECREE), rows[0].cells.keys.toList())
        assertTrue(rows[1].lawText.contains("1. 가격에"))
        assertTrue(rows[1].lawText.contains("2. 자기"))
        assertEquals(setOf(LawLevel.DECREE, LawLevel.RULE), rows[1].cells.keys)
    }

    @Test fun onlyChosenLevelsBecomeColumnsAndEmptyRowsMerge() {
        // 시행령은 고르지 않고 시행규칙만: 제1항(시행령) 줄은 비므로 법률 글이 시행규칙이 있는 줄에 합쳐진다
        val rows = CompareTable.build(tree(), setOf(LawLevel.RULE))
        assertEquals(1, rows.size)
        assertEquals(listOf(LawLevel.RULE), rows[0].cells.keys.toList())
        assertTrue(rows[0].lawText.contains("다만") && rows[0].lawText.contains("2. 자기"))
        assertEquals(listOf(LawLevel.LAW, LawLevel.RULE), CompareTable.columns(setOf(LawLevel.RULE)))
    }
}

class DeletedItemAttachmentTest {
    private val law = Article(
        ArticleNo.of("420"), "과태료",
        "제420조(과태료) ① 다음 각 호의 어느 하나에 해당하는 자에게는 과태료를 부과한다.\n" +
            "② 금융위원회는 필요하다고 인정되는 경우 자료의 제출을 요구할 수 있다.\n④ 삭제 <2024.2.13>",
    )
    private val decree = Article(ArticleNo.of("373"), "조치", "제373조(조치) 법 제420조제4항에 따라 대통령령으로 정하는 경우는 다음과 같다.")
    private val rule = Article(ArticleNo.of("374"), "조치", "제374조(조치) 법 제420조제2항에 따른 방법은 다음과 같다.")

    private fun attached(a: Article): Map<ItemPath, List<com.hdlee73.everylaw.core.model.LawNode>> {
        val tree = DelegationTreeBuilder().build(
            DelegationTreeBuilder.Input("자본시장법", a, "자본시장법 시행령", listOf(decree, rule), "자본시장법 시행규칙", emptyList(), emptyList(), emptyList()),
        )
        return ItemAttachment.attach(tree.article, tree.children)
    }

    @Test fun deletedParagraphGetsNoDelegatedRules() {
        val m = attached(law)
        assertTrue(m.keys.none { it.paragraph == 4 })
    }

    @Test fun deletedTextVariants() {
        assertTrue(ItemAttachment.isDeletedText("④ 삭제 <2024.2.13>"))
        assertTrue(ItemAttachment.isDeletedText("삭제 〈2019.4.30〉"))
        assertTrue(ItemAttachment.isDeletedText("3. 삭제"))
        assertTrue(!ItemAttachment.isDeletedText("② 삭제된 조문을 제외한다."))
    }

    @Test fun deletedArticleGetsNothing() {
        val m = attached(Article(ArticleNo.of("420"), "삭제", "제420조 삭제 <2024.2.13>"))
        assertTrue(m.isEmpty())
    }
}

class CitedProvisionTest {
    private val law = Article(
        ArticleNo.of("34"), "건전경영의 지도",
        "제34조(건전경영의 지도) ① 은행은 은행업을 경영할 때 자기자본을 충실하게 하고 적정한 유동성을 유지하는 등 경영의 건전성을 확보하여야 한다.\n" +
            "② 은행은 경영의 건전성을 유지하기 위하여 다음 각 호의 사항에 관하여 대통령령으로 정하는 바에 따라 금융위원회가 정하는 경영지도기준을 지켜야 한다.\n" +
            "1. 자본의 적정성에 관한 사항\n2. 자산의 건전성에 관한 사항\n3. 유동성에 관한 사항\n4. 그 밖에 경영의 건전성 확보를 위하여 필요한 사항",
    )
    private val delegated = Article(ArticleNo.of("20"), "경영지도기준 등", "제20조(경영지도기준 등) ① 법 제34조제2항에 따른 경영지도기준에는 다음 각 호의 내용이 포함되어야 한다.\n1. 자기자본비율")
    private val citedOnly = Article(
        ArticleNo.of("29"), "민감정보 및 고유식별정보의 처리",
        "제29조(민감정보 및 고유식별정보의 처리) ① 금융위원회는 다음 각 호의 사무를 수행하기 위하여 불가피한 경우 민감정보를 처리할 수 있다.\n1. 법 제34조제2항제4호에 따른 사무",
    )

    private fun split() = DelegationTreeBuilder().buildSplit(
        DelegationTreeBuilder.Input("은행법", law, "은행법 시행령", listOf(delegated, citedOnly), null, emptyList(), emptyList(), emptyList()),
    )

    @Test fun citationOfNonDelegatingItemIsNotDelegation() {
        val s = split()
        assertEquals(listOf(ArticleNo.of("20")), s.root.children.map { it.article.number })
        assertEquals(listOf(ArticleNo.of("29")), s.cited.map { it.article.number })
    }

    @Test fun decreeWithoutLawCitationStaysDelegated() {
        val s = DelegationTreeBuilder().buildSplit(
            DelegationTreeBuilder.Input("은행법", law, "은행법 시행령", listOf(Article(ArticleNo.of("5"), "기타", "제5조(기타) 이 영에서 정한다.")), null, emptyList(), emptyList(), emptyList()),
        )
        assertEquals(1, s.root.children.size)
        assertTrue(s.cited.isEmpty())
    }
}

class CompareAnchorTest {
    @Test fun delegatedItemStartsAtItsOwnLine() {
        val law = Article(
            ArticleNo.of("34"), "건전경영",
            "제34조(건전경영) ① 첫째 항이다.\n② 둘째 항은 대통령령으로 정한다.\n③ 셋째 항은 대통령령으로 정한다.",
        )
        val decree = Article(ArticleNo.of("9"), "세부", "제9조(세부) 법 제34조제3항에 따라 정한다.")
        val root = DelegationTreeBuilder().build(
            DelegationTreeBuilder.Input("은행법", law, "은행법 시행령", listOf(decree), null, emptyList(), emptyList(), emptyList()),
        )
        val row = CompareTable.build(root, setOf(LawLevel.DECREE)).single { it.cells.isNotEmpty() }
        assertTrue(row.lawText.substring(row.anchorStart).startsWith("③"))
    }
}

class HwpxExporterTest {
    private val law = Article(
        ArticleNo.of("34"), "건전경영",
        "제34조(건전경영) ① 첫째 항이다.\n② 둘째 항은 대통령령으로 정한다.\n③ 셋째 항은 대통령령으로 정한다.",
    )
    private val decree = Article(ArticleNo.of("9"), "세부", "제9조(세부) 법 제34조제3항에 따라 정한다. & <기타>")

    private fun tree(): com.hdlee73.everylaw.core.model.DelegationTree {
        val root = DelegationTreeBuilder().build(
            DelegationTreeBuilder.Input("은행법", law, "은행법 시행령", listOf(decree), null, emptyList(), emptyList(), emptyList()),
        )
        return com.hdlee73.everylaw.core.model.DelegationTree(
            query = com.hdlee73.everylaw.core.model.LawQuery("은행법 제34조", "은행법", ArticleNo.of("34")),
            law = com.hdlee73.everylaw.core.model.LawSummary("1", "1", "은행법", null, "법률", null),
            root = root,
        )
    }

    private fun entries(bytes: ByteArray) =
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.associate { it.name to z.readBytes().toString(Charsets.UTF_8) } }

    @Test fun writesValidPackageForBothModes() {
        for (compare in listOf(false, true)) {
            val out = java.io.ByteArrayOutputStream()
            com.hdlee73.everylaw.core.domain.HwpxExporter.write(tree(), compare, setOf(LawLevel.DECREE), out)
            val files = entries(out.toByteArray())
            assertEquals("application/hwp+zip", files["mimetype"])
            val section = files.getValue("Contents/section0.xml")
            assertTrue(section.contains("셋째 항"))
            assertTrue(section.contains("&amp; &lt;기타&gt;"))
            assertEquals(compare, section.contains("<hp:tbl"))
            // 모든 XML이 올바른 형식인지
            files.filterKeys { it.endsWith(".xml") || it.endsWith(".hpf") || it.endsWith(".rdf") }.forEach { (_, xml) ->
                javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.byteInputStream())
            }
            java.io.File(System.getProperty("java.io.tmpdir"), "hwpx-test-${if (compare) "compare" else "seq"}.hwpx").writeBytes(out.toByteArray())
        }
    }
}
