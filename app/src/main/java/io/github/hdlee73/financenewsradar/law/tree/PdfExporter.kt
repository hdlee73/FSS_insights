package io.github.hdlee73.financenewsradar.law.tree

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import io.github.hdlee73.financenewsradar.BuildConfig
import com.hdlee73.everylaw.core.domain.CompareTable
import com.hdlee73.everylaw.core.domain.ItemAttachment
import com.hdlee73.everylaw.core.model.ArticleItem
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.PdfExtras
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode
import com.hdlee73.everylaw.core.model.formatYmd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 검색 결과(조문·하위 규정 트리·판례)를 A4 PDF로 만든다.
 * 안드로이드 기본 글꼴(Noto Sans CJK)로 그리므로 한글이 깨지지 않는다.
 */
/** PDF에 담을 범위. 법률 조문은 항상 들어가고, 나머지는 고른 것만 담는다. */
data class PdfOptions(
    val levels: Set<LawLevel> = LawLevel.entries.toSet(),
    val precedentList: Boolean = true,
    val precedentBodies: Boolean = false,
    val relatedArticles: Boolean = false,
    /** true 면 고른 단계(levels)를 열로 나란히 놓는 비교표(가로), false 면 현행 방식(위에서 아래로 이어 쓰기) */
    val compare: Boolean = false,
    val paper: PdfPaper = PdfPaper.A4,
    /** 저장 형식: PDF 또는 한글(HWPX). HWPX는 판례·인용 조문 없이 조문·하위 규정·비교표만 담는다. */
    val format: SaveFormat = SaveFormat.PDF,
)

enum class SaveFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    HWPX("한글(HWPX)", "hwpx", "application/hwp+zip"),
}

/** 용지 크기(1/72 인치, 세로 기준). 비교표는 가로로 돌려 쓴다. B4는 ISO 250x353mm. */
enum class PdfPaper(val label: String, val w: Int, val h: Int) {
    A4("A4", 595, 842),
    B4("B4", 709, 1001),
    A3("A3", 842, 1191),
}

object PdfExporter {

    private const val PAGE_W = 595 // A4, 1/72 inch
    private const val PAGE_H = 842
    internal const val MARGIN = 48f

    internal val levelColor = mapOf(
        LawLevel.LAW to 0xFF6750A4.toInt(),
        LawLevel.DECREE to 0xFF0B5CAD.toInt(),
        LawLevel.RULE to 0xFF0F6B50.toInt(),
        LawLevel.SUPERVISORY_REGULATION to 0xFF9A4400.toInt(),
        LawLevel.ENFORCEMENT_DETAIL to 0xFF9C2452.toInt(),
    )

    fun fileName(tree: DelegationTree, ext: String = "pdf"): String {
        val base = "${tree.law.abbreviation ?: tree.law.name}_${tree.query.target}"
        val asOf = tree.asOf?.let { "_${it}기준" }.orEmpty()
        return (base + asOf).replace(Regex("""[\\/:*?"<>|\s]"""), "_") + "." + ext
    }

    /** 고른 단계에 없는 노드는 빼고, 그 아래 노드는 한 단계 위로 올린다. */
    private fun keepLevels(children: List<LawNode>, levels: Set<LawLevel>): List<LawNode> =
        children.flatMap { c ->
            val kept = keepLevels(c.children, levels)
            if (c.level in levels) listOf(c.copy(children = kept)) else kept
        }

    suspend fun write(
        tree: DelegationTree,
        options: PdfOptions,
        extras: PdfExtras,
        out: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val compare = options.compare && CompareTable.columns(options.levels).size >= 2
        // 비교표는 가로, 현행 방식은 세로. 용지 크기는 사용자가 고른다.
        val w = if (compare) Writer(doc, options.paper.h, options.paper.w) else Writer(doc, options.paper.w, options.paper.h)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
        val purple = 0xFF4F378B.toInt()

        w.text("FSS Insights 위임 법령 조회 결과", 18f, bold = true, color = purple)
        w.text("${tree.law.name} ${tree.query.target}", 14f, bold = true)
        w.text(
            buildString {
                append("기준: ")
                append(tree.version?.let { "${it.effectiveDateLabel} 시행 (${it.status ?: "연혁"})" } ?: "현행")
                append("   ·   출력: $now")
                if (tree.isSample) append("   ·   데모 데이터(실제 조문 아님)")
            },
            9f, color = Color.DKGRAY,
        )
        val scope = (listOf("법률") + LawLevel.entries.filter { it != LawLevel.LAW && it in options.levels }.map { it.label } +
            listOfNotNull(
                if (options.precedentList) "판례 목록" else null,
                if (options.precedentBodies) "판례 본문" else null,
                if (options.relatedArticles) "인용 조문" else null,
            )).joinToString(", ")
        if (compare) {
            val cols = CompareTable.columns(options.levels)
            w.text("비교 방식: ${cols.size}단 비교 (${cols.joinToString("·") { it.label }}) · ${options.paper.label} 가로", 9f, color = Color.DKGRAY)
        } else w.text("포함 범위: $scope", 9f, color = Color.DKGRAY)
        tree.warnings.forEach { w.text("※ $it", 8.5f, color = Color.GRAY) }
        w.gap(10f)

        fun nodeBlock(n: LawNode, depth: Int) {
            val indent = depth * 14f
            w.text("[${n.level.label}] ${n.sourceName}", 9f, bold = true, color = levelColor.getValue(n.level), indent = indent)
            w.text(n.displayHeading, 11f, bold = true, indent = indent)
            w.text(n.article.body, 9.5f, indent = indent, extraSpacing = 2f)
            w.gap(6f)
            n.children.forEach { nodeBlock(it, depth + 1) }
        }

        val root = tree.root
        if (compare) {
            PdfCompare.draw(w, root, options.levels)
        } else {
        // 법률 조문: 항·호·목 줄 바로 아래에 그 위치가 위임한 하위 규정을 둔다.
        val children = keepLevels(root.children, options.levels)
        val attached = ItemAttachment.attach(root.article, children)
        w.text("[${root.level.label}] ${root.sourceName}", 9f, bold = true, color = levelColor.getValue(root.level))
        w.text(root.article.heading, 11f, bold = true)
        val parsed = root.article.parsed
        if (parsed.lead.isNotBlank()) w.text(parsed.lead, 9.5f, extraSpacing = 2f)
        fun item(i: ArticleItem, depth: Int) {
            w.text(i.text, 9.5f, indent = depth * 14f, extraSpacing = 2f)
            attached[i.path]?.let { nodes ->
                w.text("▼ ${i.path.display} 하위 규정", 8.5f, bold = true, color = Color.DKGRAY, indent = depth * 14f + 8f)
                nodes.forEach { nodeBlock(it, depth + 1) }
            }
            i.children.forEach { item(it, depth + 1) }
        }
        if (parsed.items.isEmpty() && parsed.lead.isBlank()) w.text(root.article.content, 9.5f, extraSpacing = 2f)
        attached[ItemPath.LEAD]?.let { nodes ->
            w.text("▼ 각 호 외의 부분 하위 규정", 8.5f, bold = true, color = Color.DKGRAY, indent = 8f)
            nodes.forEach { nodeBlock(it, 1) }
        }
        parsed.items.forEach { item(it, 0) }
        attached[ItemPath()]?.let { nodes ->
            w.gap(4f)
            w.text("▼ 조 전체에 연결된 하위 규정", 8.5f, bold = true, color = Color.DKGRAY, indent = 8f)
            nodes.forEach { nodeBlock(it, 1) }
        }
        }

        val cited = keepLevels(tree.cited, options.levels)
        if (cited.isNotEmpty()) {
            w.gap(8f)
            w.text("인용된 법규 (${cited.sumOf { 1 + it.descendantCount }}건)", 13f, bold = true, color = purple)
            w.text("이 조문을 인용했지만 위임받은 규정은 아닙니다.", 9f, color = Color.DKGRAY)
            cited.forEach { n ->
                nodeBlock(n, 0)
                if (n.delegatedBy.isNotEmpty()) w.text("위임 근거: " + n.delegatedBy.joinToString(", ") { "${root.sourceName} ${it.heading}" }, 8.5f, color = Color.DKGRAY, indent = 0f)
            }
        }

        if (options.relatedArticles) {
            w.gap(8f)
            w.text("인용 조문 (${extras.relatedArticles.size}건)", 13f, bold = true, color = purple)
            if (extras.relatedArticles.isEmpty()) w.text("본문이 인용한 같은 법의 다른 조문이 없거나 불러오지 못했습니다.", 9.5f, color = Color.GRAY)
            extras.relatedArticles.forEach { a ->
                w.text("${tree.law.name} ${a.heading}", 10.5f, bold = true)
                w.text(a.body, 9.5f, indent = 10f, extraSpacing = 2f)
                w.gap(6f)
            }
        }

        if (options.precedentList) {
            w.gap(8f)
            w.text("관련 판례 (${tree.precedents.size}건)", 13f, bold = true, color = purple)
            if (tree.precedents.isEmpty()) w.text("찾은 판례가 없습니다.", 9.5f, color = Color.GRAY)
            tree.precedents.forEach { p ->
                w.text("• ${p.caseName}", 10f, bold = true)
                w.text(listOfNotNull(p.court, p.caseNumber, p.decisionDate?.let(::formatYmd)).joinToString(" · "), 9f, color = Color.DKGRAY, indent = 10f)
                w.gap(3f)
            }
        }

        if (options.precedentBodies) {
            w.gap(8f)
            w.text("판례 본문 (${extras.precedents.size}건)", 13f, bold = true, color = purple)
            if (extras.precedents.isEmpty()) w.text("불러온 판례 본문이 없습니다.", 9.5f, color = Color.GRAY)
            extras.precedents.forEach { d ->
                w.text(d.summary.caseName, 10.5f, bold = true)
                w.text(listOfNotNull(d.summary.court, d.summary.caseNumber, d.summary.decisionDate?.let(::formatYmd)).joinToString(" · "), 9f, color = Color.DKGRAY, indent = 10f)
                listOf("판시사항" to d.holdings, "판결요지" to d.gist, "참조조문" to d.referencedArticles, "참조판례" to d.referencedCases).forEach { (title, body) ->
                    if (body.isNotBlank()) {
                        w.text("[$title]", 9f, bold = true, indent = 10f)
                        w.text(body, 9f, indent = 10f, extraSpacing = 2f)
                    }
                }
                w.gap(8f)
            }
        }

        w.gap(12f)
        w.text(
            "출처: 국가법령정보센터 공동활용 API (law.go.kr). 법적 판단의 근거로는 반드시 원문을 확인하세요.\n" +
                "FSS Insights ${BuildConfig.VERSION_NAME} · 만든이 이현덕 (hdlee73@gmail.com)",
            8f, color = Color.GRAY,
        )
        w.finish()
        doc.writeTo(out)
        doc.close()
    }

    /** 공유 시트용: 캐시에 저장한 뒤 다른 앱으로 보낸다. */
    /** 한글(HWPX) 문서로 쓴다. 현행/비교표 선택과 포함 단계는 PDF와 같다. */
    suspend fun writeHwpx(tree: DelegationTree, options: PdfOptions, out: OutputStream) = withContext(Dispatchers.IO) {
        com.hdlee73.everylaw.core.domain.HwpxExporter.write(tree, options.compare, options.levels, out)
    }

    suspend fun share(context: Context, tree: DelegationTree, options: PdfOptions, extras: PdfExtras) {
        val dir = File(context.cacheDir, "law_pdf").apply { mkdirs() }
        val file = File(dir, fileName(tree, options.format.ext))
        file.outputStream().use { if (options.format == SaveFormat.HWPX) writeHwpx(tree, options, it) else write(tree, options, extras, it) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(options.format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "${options.format.label} 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 문단을 줄 단위로 쪼개 페이지를 넘기며 그린다. */
    internal class Writer(private val doc: PdfDocument, val pageW: Int = PAGE_W, val pageH: Int = PAGE_H) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        var y = MARGIN

        val canvas: Canvas get() = (page ?: newPage()).canvas

        /** 다음 쪽으로 넘긴다 */
        fun breakPage() {
            newPage()
        }

        private fun newPage(): PdfDocument.Page {
            page?.let { finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            page = p
            y = MARGIN
            return p
        }

        private fun finishPage(p: PdfDocument.Page) {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }
            p.canvas.drawText("- $pageNo -", pageW / 2f - 10f, pageH - 24f, paint)
            doc.finishPage(p)
        }

        fun gap(h: Float) {
            y += h
        }

        fun text(
            value: String,
            size: Float,
            bold: Boolean = false,
            color: Int = Color.BLACK,
            indent: Float = 0f,
            extraSpacing: Float = 1f,
        ) {
            if (value.isBlank()) return
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val width = (pageW - MARGIN * 2 - indent).toInt().coerceAtLeast(80)
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(extraSpacing, 1f)
                .build()
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                val h = (bottom - top).toFloat()
                if (page == null || y + h > pageH - MARGIN) newPage()
                val c = canvas
                c.save()
                c.translate(MARGIN + indent, y - top)
                c.clipRect(0, top, width, bottom)
                layout.draw(c)
                c.restore()
                y += h
            }
            y += 2f
        }

        fun finish() {
            (page ?: newPage()).let { finishPage(it) }
            page = null
        }
    }
}
