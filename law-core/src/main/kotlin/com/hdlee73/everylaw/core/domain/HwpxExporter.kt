package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.ArticleItem
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode
import com.hdlee73.everylaw.core.model.PdfExtras
import com.hdlee73.everylaw.core.model.formatYmd
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
/** 한글(HWPX) 저장 범위. PDF 저장 창과 같은 항목이다. 용지 크기는 1/72 인치(pt) 세로 기준. */
data class HwpxOptions(
    val compare: Boolean = false,
    val levels: Set<LawLevel> = LawLevel.entries.toSet(),
    val paperLabel: String = "A4",
    val paperWidthPt: Int = 595,
    val paperHeightPt: Int = 842,
    val precedentList: Boolean = true,
    val precedentBodies: Boolean = false,
    val relatedArticles: Boolean = false,
    val extras: PdfExtras = PdfExtras(),
    /** 맨 끝에 덧붙일 한 줄(앱 이름·버전·만든이) */
    val footer: String = "",
)

/**
 * 조회 결과를 한글(HWPX) 문서로 만든다. HWPX는 XML을 zip으로 묶은 개방형 형식이라 외부 라이브러리 없이 쓴다.
 * 내용과 서식은 PDF 저장과 같게 맞춘다: 단계별 색 제목, 같은 글자 크기·들여쓰기, 비교표 제목줄(가운데 정렬·색 바탕),
 * 인용된 법규·인용 조문·판례 목록·판례 본문, 쪽 번호.
 * 문서 뼈대(스타일 정의 등)는 resources/hwpx 의 템플릿을 쓰고, 본문(section0.xml)만 만든다.
 */
object HwpxExporter {
    // header.xml 의 글자 모양(charPr) 번호
    private const val C_BODY = 12
    private const val C_ITEM_HEAD = 13
    private const val C_TITLE = 9
    private const val C_SUBTITLE = 10
    private const val C_META = 11
    private const val C_SECTION = 14
    private const val C_GRAY = 15
    private const val C_CELL_BOLD = 16
    private const val C_ITEM_BOLD = 22
    private const val C_FOOT = 23
    private const val C_HDR = 8
    private fun cLevel(lv: LawLevel) = 17 + lv.ordinal

    // paraPr: 20 가운데, 21.. 왼쪽(들여쓰기 0~6단계)
    private const val P_CENTER = 20
    private fun pIndent(depth: Int) = 21 + depth.coerceIn(0, 6)

    private const val MARGIN = 4800 // 48pt, PDF와 같은 바깥 여백

    private fun res(name: String): String =
        HwpxExporter::class.java.getResourceAsStream("/hwpx/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("HWPX 템플릿 $name 을 찾을 수 없어요")

    fun write(tree: DelegationTree, compare: Boolean, levels: Set<LawLevel>, out: OutputStream) =
        write(tree, HwpxOptions(compare = compare, levels = levels), out)

    fun write(tree: DelegationTree, options: HwpxOptions, out: OutputStream) {
        val levels = options.levels
        val landscape = options.compare && CompareTable.columns(levels).size >= 2
        val pageW = if (landscape) options.paperHeightPt * 100 else options.paperWidthPt * 100
        val pageH = if (landscape) options.paperWidthPt * 100 else options.paperHeightPt * 100
        val body = Body().also { it.pageTextWidth = pageW - MARGIN * 2 }
        body.paragraph("FSS Insights 위임 법령 조회 결과", C_TITLE)
        body.paragraph("${tree.law.name} ${tree.query.target}", C_SUBTITLE)
        body.paragraph(
            "기준: " + (tree.version?.let { "${it.effectiveDateLabel} 시행 (${it.status ?: "연혁"})" } ?: "현행") + "   ·   출력: ${LocalDate.now()}" +
                if (tree.isSample) "   ·   데모 데이터(실제 조문 아님)" else "",
            C_META,
        )
        if (landscape) {
            val cols = CompareTable.columns(levels)
            body.paragraph("비교 방식: ${cols.size}단 비교 (${cols.joinToString("·") { it.label }}) · ${options.paperLabel} 가로", C_META)
        } else {
            val scope = (listOf("법률") + LawLevel.entries.filter { it != LawLevel.LAW && it in levels }.map { it.label } +
                listOfNotNull(
                    if (options.precedentList) "판례 목록" else null,
                    if (options.precedentBodies) "판례 본문" else null,
                    if (options.relatedArticles) "인용 조문" else null,
                )).joinToString(", ")
            body.paragraph("포함 범위: $scope", C_META)
        }
        tree.warnings.forEach { body.paragraph("※ $it", C_GRAY) }
        body.paragraph("")

        if (landscape) compareTable(body, tree.root, levels, pageW, pageH) else sequential(body, tree.root, levels)

        val cited = keep(tree.cited, levels)
        if (cited.isNotEmpty()) {
            body.paragraph("")
            body.paragraph("인용된 법규 (${cited.sumOf { 1 + it.descendantCount }}건)", C_SECTION)
            body.paragraph("이 조문을 인용했지만 위임받은 규정은 아닙니다.", C_META)
            cited.forEach { n ->
                nodeBlock(body, n, 0)
                if (n.delegatedBy.isNotEmpty()) body.paragraph("위임 근거: " + n.delegatedBy.joinToString(", ") { "${tree.root.sourceName} ${it.heading}" }, C_META)
            }
        }

        if (options.relatedArticles) {
            body.paragraph("")
            body.paragraph("인용 조문 (${options.extras.relatedArticles.size}건)", C_SECTION)
            if (options.extras.relatedArticles.isEmpty()) body.paragraph("본문이 인용한 같은 법의 다른 조문이 없거나 불러오지 못했습니다.", C_GRAY)
            options.extras.relatedArticles.forEach { a ->
                body.paragraph("${tree.law.name} ${a.heading}", C_ITEM_BOLD)
                a.body.lines().forEach { body.paragraph(it, C_BODY, 1) }
                body.paragraph("")
            }
        }

        if (options.precedentList) {
            body.paragraph("")
            body.paragraph("관련 판례 (${tree.precedents.size}건)", C_SECTION)
            if (tree.precedents.isEmpty()) body.paragraph("찾은 판례가 없습니다.", C_GRAY)
            tree.precedents.forEach { p ->
                body.paragraph("• ${p.caseName}", C_ITEM_BOLD)
                body.paragraph(listOfNotNull(p.court, p.caseNumber, p.decisionDate?.let(::formatYmd)).joinToString(" · "), C_META, 1)
            }
        }

        if (options.precedentBodies) {
            body.paragraph("")
            body.paragraph("판례 본문 (${options.extras.precedents.size}건)", C_SECTION)
            if (options.extras.precedents.isEmpty()) body.paragraph("불러온 판례 본문이 없습니다.", C_GRAY)
            options.extras.precedents.forEach { d ->
                body.paragraph(d.summary.caseName, C_ITEM_BOLD)
                body.paragraph(listOfNotNull(d.summary.court, d.summary.caseNumber, d.summary.decisionDate?.let(::formatYmd)).joinToString(" · "), C_META, 1)
                listOf("판시사항" to d.holdings, "판결요지" to d.gist, "참조조문" to d.referencedArticles, "참조판례" to d.referencedCases).forEach { (title, text) ->
                    if (text.isNotBlank()) {
                        body.paragraph("[$title]", C_CELL_BOLD, 1)
                        text.lines().forEach { body.paragraph(it, C_BODY, 1) }
                    }
                }
                body.paragraph("")
            }
        }

        body.paragraph("")
        body.paragraph("출처: 국가법령정보센터 공동활용 API (law.go.kr). 법적 판단의 근거로는 반드시 원문을 확인하세요.", C_FOOT)
        options.footer.lines().filter { it.isNotBlank() }.forEach { body.paragraph(it, C_FOOT) }

        val first = res("first_paragraph.xml")
            .replace("{{LANDSCAPE}}", if (landscape) "NARROWLY" else "WIDELY")
            .replace("{{WIDTH}}", pageW.toString()).replace("{{HEIGHT}}", pageH.toString())
            .replace("{{MARGIN}}", MARGIN.toString())
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
        body.paragraph("[${n.level.label}] ${n.sourceName}", cLevel(n.level), depth)
        body.paragraph(n.displayHeading, C_ITEM_HEAD, depth)
        n.article.body.lines().forEach { body.paragraph(it, C_BODY, depth) }
        body.paragraph("")
        n.children.forEach { nodeBlock(body, it, depth + 1) }
    }

    /** 현행 방식: 법률 조문의 항·호·목 줄 바로 아래에 그 위치가 위임한 하위 규정을 이어서 쓴다. */
    private fun sequential(body: Body, root: LawNode, levels: Set<LawLevel>) {
        val attached = ItemAttachment.attach(root.article, keep(root.children, levels))
        val parsed = root.article.parsed
        body.paragraph("[${root.level.label}] ${root.sourceName}", cLevel(root.level))
        body.paragraph(root.article.heading, C_ITEM_HEAD)
        if (parsed.lead.isNotBlank()) parsed.lead.lines().forEach { body.paragraph(it, C_BODY) }
        fun group(path: ItemPath, depth: Int, label: String) {
            attached[path]?.let { nodes ->
                body.paragraph("▼ $label 하위 규정", C_META, depth + 1)
                nodes.forEach { nodeBlock(body, it, depth + 1) }
            }
        }
        fun item(i: ArticleItem, depth: Int) {
            i.text.lines().forEach { body.paragraph(it, C_BODY, depth) }
            group(i.path, depth, i.path.display)
            i.children.forEach { item(it, depth + 1) }
        }
        if (parsed.items.isEmpty() && parsed.lead.isBlank()) root.article.content.lines().forEach { body.paragraph(it, C_BODY) }
        group(ItemPath.LEAD, 0, "각 호 외의 부분")
        parsed.items.forEach { item(it, 0) }
        group(ItemPath(), 0, "조 전체에 연결된")
    }

    private class Line(val text: String, val char: Int, val para: Int = pIndent(0))

    /** 글자 모양 번호별 글자 크기(1/100 pt). header.xml 의 charPr 높이와 같아야 한다. */
    private val charSize = mapOf(
        0 to 1000, 2 to 900, 8 to 950, 9 to 1800, 10 to 1400, 11 to 900, 12 to 950, 13 to 1100, 14 to 1300,
        15 to 850, 16 to 900, 17 to 900, 18 to 900, 19 to 900, 20 to 900, 21 to 900, 22 to 1000, 23 to 800, 24 to 850, 25 to 850,
    )
    private fun sizeOf(char: Int) = charSize[char] ?: 1000

    /** 줄 간격 130%에 글꼴 여유를 더한 한 줄 높이(글자 크기의 배수) */
    private const val LINE_FACTOR = 1.38
    private const val CELL_PAD = 400

    /** 한글·한자·전각 기호는 글자 크기만큼, 영문·숫자·공백은 그보다 좁게 어림해 [widthUnits] 안에서 줄을 나눈다. */
    private fun wrap(text: String, widthUnits: Double, size: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var w = 0.0
        for (ch in text) {
            val cw = size * (if (ch.code >= 0x1100) 1.0 else 0.55)
            if (cur.isNotEmpty() && w + cw > widthUnits) {
                out += cur.toString()
                cur.setLength(0); w = 0.0
                if (ch == ' ') continue
            }
            cur.append(ch); w += cw
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun visual(lines: List<Line>, widthUnits: Double): List<Line> =
        lines.flatMap { l -> wrap(l.text, widthUnits, sizeOf(l.char)).map { Line(it, l.char, l.para) } }

    /**
     * 비교표: 법률을 첫 열로, 고른 단계를 열로 놓는다. 하위 규정 칸은 위임받은 항·호가 시작하는 줄과 같은 줄에서 시작한다.
     * 한글은 한 칸이 쪽보다 길면 나누지 못해 표가 쪽 밖으로 넘치므로, 줄 수를 어림해 칸을 쪽 높이에 맞는 행으로 직접 나눈다.
     * (PDF 비교표가 쪽마다 칸을 끊어 그리는 것과 같다.)
     */
    private fun compareTable(body: Body, root: LawNode, levels: Set<LawLevel>, pageW: Int, pageH: Int) {
        val cols = CompareTable.columns(levels)
        val rows = CompareTable.build(root, levels)
        val total = pageW - MARGIN * 2
        val colW = total / cols.size
        val cellChar = if (cols.size <= 3) 2 else 24
        val cellBold = if (cols.size <= 3) C_CELL_BOLD else 25
        val headH = 1800
        val wrapW = (colW - CELL_PAD * 2) * 0.93
        val lineH = sizeOf(cellChar) * LINE_FACTOR
        val pageAvail = (pageH - MARGIN * 2) * 0.95

        // 한 줄이 한 쪽 칸(행) 단위로 들어갈 조각들
        class Chunk(val cells: List<List<Line>>)
        val chunks = mutableListOf<Chunk>()
        var y = body.usedHeight() % pageAvail + headH
        rows.forEach { row ->
            val lawLines = visual(row.lawText.lines().map { Line(it, cellChar) }, wrapW)
            val leadLogical = row.lawText.take(row.anchorStart.coerceIn(0, row.lawText.length)).count { it == '\n' }
            val lead = row.lawText.lines().take(leadLogical).sumOf { wrap(it, wrapW, sizeOf(cellChar)).size }
            val colLines = listOf(lawLines) + cols.drop(1).map { lv ->
                val lines = mutableListOf<Line>()
                row.cells[lv].orEmpty().forEachIndexed { i, n ->
                    if (i > 0) lines += Line("", cellChar)
                    lines += Line(n.displayHeading.ifBlank { n.sourceName }, cellBold)
                    if (lv.ordinal >= LawLevel.SUPERVISORY_REGULATION.ordinal) lines += Line(n.sourceName, C_GRAY)
                    n.article.body.lines().forEach { lines += Line(it, cellChar) }
                }
                val vis = visual(lines, wrapW)
                if (vis.isEmpty()) vis else List(lead) { Line("", cellChar) } + vis
            }
            val n = colLines.maxOf { it.size }
            var idx = 0
            while (idx < n) {
                var k = ((pageAvail - y - CELL_PAD * 2) / lineH).toInt()
                if (k < 3 && y > headH) { y = headH.toDouble(); k = ((pageAvail - y - CELL_PAD * 2) / lineH).toInt() }
                val take = k.coerceAtLeast(1).coerceAtMost(n - idx)
                chunks += Chunk(colLines.map { it.drop(idx).take(take) })
                y += take * lineH + CELL_PAD * 2
                idx += take
                if (idx < n) y = headH.toDouble()
            }
        }
        if (chunks.isEmpty()) chunks += Chunk(cols.map { emptyList() })

        val sb = StringBuilder()
        sb.append("""<hp:tbl id="${body.nextId()}" zOrder="0" numberingType="TABLE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" lock="0" dropcapstyle="None" pageBreak="CELL" repeatHeader="1" rowCnt="${chunks.size + 1}" colCnt="${cols.size}" cellSpacing="0" borderFillIDRef="3" noAdjust="0">""")
        val tableH = headH + chunks.sumOf { c -> (c.cells.maxOf { it.size } * lineH + CELL_PAD * 2).toInt() }
        sb.append("""<hp:sz width="${colW * cols.size}" widthRelTo="ABSOLUTE" height="$tableH" heightRelTo="ABSOLUTE" protect="0"/>""")
        sb.append("""<hp:pos treatAsChar="1" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>""")
        sb.append("""<hp:outMargin left="0" right="0" top="0" bottom="0"/><hp:inMargin left="$CELL_PAD" right="$CELL_PAD" top="$CELL_PAD" bottom="$CELL_PAD"/>""")
        fun cell(row: Int, col: Int, lines: List<Line>, header: LawLevel?, height: Int) {
            if (col == 0) sb.append("<hp:tr>")
            val fill = if (header != null) 4 + header.ordinal else 3
            sb.append("""<hp:tc name="" header="${if (header != null) 1 else 0}" hasMargin="0" protect="0" editable="0" dirty="0" borderFillIDRef="$fill">""")
            sb.append("""<hp:subList id="" textDirection="HORIZONTAL" lineWrap="BREAK" vertAlign="${if (header != null) "CENTER" else "TOP"}" linkListIDRef="0" linkListNextIDRef="0" textWidth="0" textHeight="0" hasTextRef="0" hasNumRef="0">""")
            (lines.ifEmpty { listOf(Line("", cellChar)) }).forEach { sb.append(paragraphXml(body.nextId(), it.text, it.char, it.para)) }
            sb.append("</hp:subList>")
            sb.append("""<hp:cellAddr colAddr="$col" rowAddr="$row"/><hp:cellSpan colSpan="1" rowSpan="1"/><hp:cellSz width="$colW" height="$height"/><hp:cellMargin left="$CELL_PAD" right="$CELL_PAD" top="$CELL_PAD" bottom="$CELL_PAD"/></hp:tc>""")
            if (col == cols.size - 1) sb.append("</hp:tr>")
        }
        // 제목 줄: 단계 색 바탕에 흰 글씨, 가운데 정렬
        cols.forEachIndexed { c, lv -> cell(0, c, listOf(Line(lv.label, C_HDR, P_CENTER)), lv, headH) }
        chunks.forEachIndexed { r, chunk ->
            val h = (chunk.cells.maxOf { it.size } * lineH + CELL_PAD * 2).toInt()
            cols.indices.forEach { c -> cell(r + 1, c, chunk.cells[c], null, h) }
        }
        sb.append("</hp:tbl>")
        body.raw("""<hp:p id="${body.nextId()}" paraPrIDRef="0" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="0">$sb<hp:t/></hp:run></hp:p>""")
    }

    private class Body {
        private val sb = StringBuilder()
        private var id = 1000000
        fun nextId(): Int = id++
        /** [depth]는 들여쓰기 단계(한 단계 14pt) */
        fun paragraph(text: String, char: Int = C_BODY, depth: Int = 0) {
            sb.append(paragraphXml(nextId(), text, char, pIndent(depth)))
            used += sizeOf(char) * 1.3 * wrap(text, (pageTextWidth - depth * 1400).toDouble(), sizeOf(char)).size
        }
        /** 표 앞에 이미 쓴 내용의 높이 어림(쪽 첫머리 채우기 계산용) */
        private var used = 0.0
        var pageTextWidth = 0
        fun usedHeight(): Double = used
        fun raw(xml: String) { sb.append(xml) }
        fun xml(): String = sb.toString()
    }

    private fun paragraphXml(id: Int, text: String, char: Int, para: Int): String =
        """<hp:p id="$id" paraPrIDRef="$para" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0"><hp:run charPrIDRef="$char">""" +
            (if (text.isEmpty()) "<hp:t/>" else "<hp:t>${esc(text)}</hp:t>") + "</hp:run></hp:p>"

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;")
            c == '\t' || c == '\n' || c == '\r' || c >= ' ' -> append(c)
        }
    }
}
