package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.ArticleItem
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 조회 결과를 한글(HWPX) 문서로 만든다. HWPX는 XML을 zip으로 묶은 개방형 형식이라 외부 라이브러리 없이 쓴다.
 * [compare]가 true면 법률과 [levels]에서 고른 단계를 열로 나란히 놓은 비교표(가로), 아니면 조문 아래에 하위 규정을 이어 쓴다.
 * 문서 뼈대(스타일 정의 등)는 resources/hwpx 의 템플릿을 쓰고, 본문(section0.xml)만 만든다.
 */
object HwpxExporter {
    private const val CHAR_NORMAL = 0
    private const val CHAR_SMALL = 2
    private const val CHAR_TITLE = 5
    private const val CHAR_HEADING = 6
    private const val CHAR_BOLD = 7

    private const val PORTRAIT_W = 59528
    private const val PORTRAIT_H = 84186
    private const val MARGIN_SIDE = 8504

    private class Line(val text: String, val char: Int = CHAR_NORMAL)

    private fun res(name: String): String =
        HwpxExporter::class.java.getResourceAsStream("/hwpx/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("HWPX 템플릿 $name 을 찾을 수 없어요")

    fun write(tree: DelegationTree, compare: Boolean, levels: Set<LawLevel>, out: OutputStream) {
        val landscape = compare && CompareTable.columns(levels).size >= 2
        val body = Body()
        body.paragraph("FSS Insights 위임 법령 조회 결과", CHAR_TITLE)
        body.paragraph("${tree.law.name} ${tree.query.target}", CHAR_HEADING)
        body.paragraph(
            "기준: " + (tree.version?.let { "${it.effectiveDateLabel} 시행" } ?: "현행") + "   ·   출력: ${LocalDate.now()}" +
                if (tree.isSample) "   ·   데모 데이터(실제 조문 아님)" else "",
            CHAR_SMALL,
        )
        tree.warnings.forEach { body.paragraph("※ $it", CHAR_SMALL) }
        body.paragraph("")

        if (landscape) compareTable(body, tree.root, levels) else sequential(body, tree.root, levels)

        val cited = keep(tree.cited, levels)
        if (cited.isNotEmpty()) {
            body.paragraph("")
            body.paragraph("인용된 법규 (${cited.sumOf { 1 + it.descendantCount }}건)", CHAR_HEADING)
            body.paragraph("이 조문을 인용했지만 위임받은 규정은 아닙니다.", CHAR_SMALL)
            cited.forEach { n ->
                nodeBlock(body, n, 0)
                if (n.delegatedBy.isNotEmpty()) body.paragraph("위임 근거: " + n.delegatedBy.joinToString(", ") { "${tree.root.sourceName} ${it.heading}" }, CHAR_SMALL)
            }
        }
        body.paragraph("")
        body.paragraph("출처: 국가법령정보센터 공동활용 API (law.go.kr). 법적 판단의 근거로는 반드시 원문을 확인하세요.", CHAR_SMALL)

        val w = if (landscape) PORTRAIT_H else PORTRAIT_W
        val h = if (landscape) PORTRAIT_W else PORTRAIT_H
        val first = res("first_paragraph.xml")
            .replace("{{LANDSCAPE}}", if (landscape) "NARROWLY" else "WIDELY")
            .replace("{{WIDTH}}", w.toString()).replace("{{HEIGHT}}", h.toString())
        val section = res("section_head.xml") + first + body.xml() + "</hs:sec>"
        val title = esc("${tree.law.name} ${tree.query.target}")
        val hpf = res("content.hpf").replace("{{TITLE}}", title).replace("{{DATE}}", LocalDate.now().toString())

        ZipOutputStream(out).use { zip ->
            // mimetype은 맨 앞에 압축 없이 넣는다(HWPX/OPC 규칙).
            val mime = "application/hwp+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            })
            zip.write(mime); zip.closeEntry()
            fun add(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            add("version.xml", res("version.xml"))
            add("settings.xml", res("settings.xml"))
            add("META-INF/container.xml", res("container.xml"))
            add("META-INF/manifest.xml", res("manifest.xml"))
            add("META-INF/container.rdf", res("container.rdf"))
            add("Contents/content.hpf", hpf)
            add("Contents/header.xml", res("header.xml"))
            add("Contents/section0.xml", section)
            add("Preview/PrvText.txt", "FSS Insights 위임 법령 조회 결과\r\n${tree.law.name} ${tree.query.target}")
        }
    }

    private fun keep(children: List<LawNode>, levels: Set<LawLevel>): List<LawNode> =
        children.flatMap { c ->
            val kept = keep(c.children, levels)
            if (c.level in levels) listOf(c.copy(children = kept)) else kept
        }

    private fun nodeBlock(body: Body, n: LawNode, depth: Int) {
        val pad = "    ".repeat(depth)
        body.paragraph("$pad[${n.level.label}] ${n.sourceName}", CHAR_BOLD)
        body.paragraph(pad + n.displayHeading, CHAR_BOLD)
        n.article.body.lines().forEach { body.paragraph(pad + it) }
        body.paragraph("")
        n.children.forEach { nodeBlock(body, it, depth + 1) }
    }

    /** 현행 방식: 법률 조문의 항·호·목 줄 바로 아래에 그 위치가 위임한 하위 규정을 이어서 쓴다. */
    private fun sequential(body: Body, root: LawNode, levels: Set<LawLevel>) {
        val attached = ItemAttachment.attach(root.article, keep(root.children, levels))
        val parsed = root.article.parsed
        body.paragraph("[${root.level.label}] ${root.sourceName}", CHAR_BOLD)
        body.paragraph(root.article.heading, CHAR_HEADING)
        if (parsed.lead.isNotBlank()) parsed.lead.lines().forEach { body.paragraph(it) }
        fun group(path: ItemPath, depth: Int, label: String) {
            attached[path]?.let { nodes ->
                body.paragraph("    ".repeat(depth + 1) + "▼ $label 하위 규정", CHAR_SMALL)
                nodes.forEach { nodeBlock(body, it, depth + 1) }
            }
        }
        fun item(i: ArticleItem, depth: Int) {
            i.text.lines().forEach { body.paragraph("    ".repeat(depth) + it) }
            group(i.path, depth, i.path.display)
            i.children.forEach { item(it, depth + 1) }
        }
        if (parsed.items.isEmpty() && parsed.lead.isBlank()) root.article.content.lines().forEach { body.paragraph(it) }
        group(ItemPath.LEAD, 0, "각 호 외의 부분")
        parsed.items.forEach { item(it, 0) }
        group(ItemPath(), 0, "조 전체에 연결된")
    }

    /** 비교표: 법률을 첫 열로, 고른 단계를 열로 놓는다. 하위 규정 칸은 위임받은 항·호가 시작하는 줄과 같은 줄에서 시작한다. */
    private fun compareTable(body: Body, root: LawNode, levels: Set<LawLevel>) {
        val cols = CompareTable.columns(levels)
        val rows = CompareTable.build(root, levels)
        val total = PORTRAIT_H - MARGIN_SIDE * 2
        val colW = total / cols.size
        val sb = StringBuilder()
        sb.append("""<hp:tbl id="${body.nextId()}" zOrder="0" numberingType="TABLE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" lock="0" dropcapstyle="None" pageBreak="CELL" repeatHeader="1" rowCnt="${rows.size + 1}" colCnt="${cols.size}" cellSpacing="0" borderFillIDRef="3" noAdjust="0">""")
        sb.append("""<hp:sz width="${colW * cols.size}" widthRelTo="ABSOLUTE" height="${282 * (rows.size + 1)}" heightRelTo="ABSOLUTE" protect="0"/>""")
        sb.append("""<hp:pos treatAsChar="1" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>""")
        sb.append("""<hp:outMargin left="0" right="0" top="0" bottom="0"/><hp:inMargin left="510" right="510" top="141" bottom="141"/>""")
        fun cell(row: Int, col: Int, lines: List<Line>, header: Boolean) {
            if (col == 0) sb.append("<hp:tr>")
            sb.append("""<hp:tc name="" header="${if (header) 1 else 0}" hasMargin="0" protect="0" editable="0" dirty="0" borderFillIDRef="3">""")
            sb.append("""<hp:subList id="" textDirection="HORIZONTAL" lineWrap="BREAK" vertAlign="TOP" linkListIDRef="0" linkListNextIDRef="0" textWidth="0" textHeight="0" hasTextRef="0" hasNumRef="0">""")
            (lines.ifEmpty { listOf(Line("")) }).forEach { sb.append(paragraphXml(body.nextId(), it.text, it.char)) }
            sb.append("</hp:subList>")
            sb.append("""<hp:cellAddr colAddr="$col" rowAddr="$row"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="$colW" height="282"/><hp:cellMargin left="510" right="510" top="141" bottom="141"/></hp:tc>""")
            if (col == cols.size - 1) sb.append("</hp:tr>")
        }
        cols.forEachIndexed { c, lv -> cell(0, c, listOf(Line(lv.label, CHAR_BOLD)), true) }
        rows.forEachIndexed { r, row ->
            val lead = row.lawText.take(row.anchorStart.coerceIn(0, row.lawText.length)).count { it == '\n' }
            cols.forEachIndexed { c, lv ->
                if (c == 0) cell(r + 1, 0, row.lawText.lines().map { Line(it) }, false)
                else {
                    val lines = mutableListOf<Line>()
                    repeat(lead) { lines += Line("") }
                    row.cells[lv].orEmpty().forEachIndexed { i, n ->
                        if (i > 0) lines += Line("")
                        lines += Line(n.displayHeading.ifBlank { n.sourceName }, CHAR_BOLD)
                        if (lv.ordinal >= LawLevel.SUPERVISORY_REGULATION.ordinal) lines += Line(n.sourceName, CHAR_SMALL)
                        n.article.body.lines().forEach { lines += Line(it) }
                    }
                    cell(r + 1, c, lines, false)
                }
            }
        }
        sb.append("</hp:tbl>")
        body.raw("""<hp:p id="${body.nextId()}" paraPrIDRef="0" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="0">$sb<hp:t/></hp:run></hp:p>""")
    }

    private class Body {
        private val sb = StringBuilder()
        private var id = 1000000
        fun nextId(): Int = id++
        fun paragraph(text: String, char: Int = CHAR_NORMAL) { sb.append(paragraphXml(nextId(), text, char)) }
        fun raw(xml: String) { sb.append(xml) }
        fun xml(): String = sb.toString()
    }

    private fun paragraphXml(id: Int, text: String, char: Int): String =
        """<hp:p id="$id" paraPrIDRef="0" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="$char">""" +
            (if (text.isEmpty()) "<hp:t/>" else "<hp:t>${esc(text)}</hp:t>") + "</hp:run></hp:p>"

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;")
            c == '\t' || c == '\n' || c == '\r' || c >= ' ' -> append(c)
        }
    }
}
