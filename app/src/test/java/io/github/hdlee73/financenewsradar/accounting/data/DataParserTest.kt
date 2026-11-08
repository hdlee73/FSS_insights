package io.github.hdlee73.financenewsradar.accounting.data

import io.github.hdlee73.financenewsradar.accounting.ui.screens.matchesAudit
import io.github.hdlee73.financenewsradar.accounting.ui.screens.matchesBoard
import io.github.hdlee73.financenewsradar.accounting.domain.SimilarCases
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataParserTest {
    private fun seed(name: String) = File("src/main/assets/seed/$name").readText()

    @Test fun parses_seed_audit() {
        val d = DataParser.parseAudit(seed("audit_actions.json"))
        assertTrue(d.items.size >= 70)
        assertTrue("최신순 정렬", d.items.first().actionDate >= d.items.last().actionDate)
        assertTrue(d.items.any { it.flags.fine && it.findings.isNotEmpty() })
        assertTrue(d.items.all { it.company.isNotEmpty() && it.viewUrl.startsWith("https://") })
    }

    @Test fun parses_seed_board() {
        val d = DataParser.parseBoard(seed("board_index.json"))
        assertTrue(d.items.size > 1000)
        assertTrue(d.items.all { it.url.startsWith("https://") })
        assertTrue(d.items.map { it.kind }.toSet().containsAll(setOf("qna", "interp", "audit-case")))
    }

    @Test fun tolerates_missing_fields_and_garbage_rows() {
        val d = DataParser.parseAudit("""{"items":[{"company":"A"}, 5, {"company":"B","flags":{"fine":true},"findings":[{"title":"t"}]}]}""")
        assertEquals(2, d.items.size)
        assertTrue(d.items.first { it.company == "B" }.flags.fine)
        assertEquals("failed", d.items.first { it.company == "A" }.parseStatus)
    }

    @Test fun filters() {
        val d = DataParser.parseAudit(seed("audit_actions.json"))
        val all = d.items.size
        assertEquals(all, d.items.count { matchesAudit(it, "전체", "전체", "") })
        assertTrue(d.items.count { matchesAudit(it, "코스닥", "전체", "") } in 1 until all)
        assertTrue(d.items.none { matchesAudit(it, "전체", "과징금", "") && !it.flags.fine })
        val b = DataParser.parseBoard(seed("board_index.json"))
        assertTrue(b.items.any { matchesBoard(it, "", "리스") })
        assertFalse(b.items.any { matchesBoard(it, "qna", "") && it.kind != "qna" })
    }

    @Test fun similar_cases_sorted_by_closeness() {
        val d = DataParser.parseAudit(seed("audit_actions.json"))
        val c = SimilarCases.find(d.items, 3_000_000_000L, "", 5)
        assertTrue(c.size <= 5)
        val diffs = c.map { kotlin.math.abs(it.maxFindingWon!! - 3_000_000_000L) }
        assertEquals(diffs.sorted(), diffs)
    }

    @Test fun version_compare() {
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.0-debug"))
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0-debug"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }
}
