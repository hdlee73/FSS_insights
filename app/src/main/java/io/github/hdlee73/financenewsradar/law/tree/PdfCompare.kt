package io.github.hdlee73.financenewsradar.law.tree

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.hdlee73.everylaw.core.domain.CompareTable
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode

/** 3단·4단·5단 비교표를 PDF에 그린다: 법률 조문의 위임 위치마다 한 줄, 단계마다 한 열. */
internal object PdfCompare {
    private const val PAD = 4f

    fun draw(w: PdfExporter.Writer, root: LawNode, chosen: Set<LawLevel>) {
        val levels = CompareTable.columns(chosen)
        val rows = CompareTable.build(root, chosen)
        val size = if (levels.size <= 3) 9f else 8.5f
        val colW = (w.pageW - PdfExporter.MARGIN * 2) / levels.size
        val textW = (colW - PAD * 2).toInt()

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.BLACK }
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.GRAY }

        fun header() {
            val c = w.canvas
            val label = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = 9.5f; color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            levels.forEachIndexed { i, lv ->
                val x = PdfExporter.MARGIN + colW * i
                val fill = Paint().apply { color = PdfExporter.levelColor.getValue(lv) }
                c.drawRect(x, w.y, x + colW, w.y + 18f, fill)
                c.drawText(lv.label, x + PAD, w.y + 12.5f, label)
            }
            w.y += 18f
        }

        if (w.pageH - PdfExporter.MARGIN - w.y < 120f) w.breakPage()
        header()

        rows.forEach { row ->
            val texts = listOf<CharSequence>(row.lawText) + levels.drop(1).map { lv -> cellText(row.cells[lv].orEmpty(), lv) }
            val layouts = texts.map { t ->
                StaticLayout.Builder.obtain(t, 0, t.length, paint, textW.coerceAtLeast(40))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
            }
            val next = IntArray(layouts.size)
            fun remaining() = layouts.indices.any { next[it] < layouts[it].lineCount }
            if (!remaining()) return@forEach
            while (remaining()) {
                val avail = w.pageH - PdfExporter.MARGIN - w.y - PAD * 2
                val atTop = w.y <= PdfExporter.MARGIN + 20f
                val take = IntArray(layouts.size) { i ->
                    val l = layouts[i]
                    var n = 0
                    var h = 0f
                    while (next[i] + n < l.lineCount) {
                        val li = next[i] + n
                        val lh = (l.getLineBottom(li) - l.getLineTop(li)).toFloat()
                        if (h + lh > avail) break
                        h += lh; n++
                    }
                    n
                }
                if (take.all { it == 0 }) {
                    if (!atTop) { w.breakPage(); header(); continue }
                    // 한 줄도 안 들어가는 극단적인 경우: 한 줄씩이라도 그려 무한 반복을 막는다
                    layouts.indices.forEach { if (next[it] < layouts[it].lineCount) take[it] = 1 }
                }
                val heights = layouts.indices.map { i ->
                    if (take[i] == 0) 0f else (layouts[i].getLineBottom(next[i] + take[i] - 1) - layouts[i].getLineTop(next[i])).toFloat()
                }
                val rowH = (heights.maxOrNull() ?: 0f) + PAD * 2
                val c = w.canvas
                layouts.forEachIndexed { i, l ->
                    val x = PdfExporter.MARGIN + colW * i
                    if (take[i] > 0) {
                        val top = l.getLineTop(next[i])
                        val bottom = l.getLineBottom(next[i] + take[i] - 1)
                        c.save()
                        c.translate(x + PAD, w.y + PAD - top)
                        c.clipRect(0, top, textW, bottom)
                        l.draw(c)
                        c.restore()
                    }
                    c.drawRect(x, w.y, x + colW, w.y + rowH, border)
                    next[i] += take[i]
                }
                w.y += rowH
                if (remaining()) { w.breakPage(); header() }
            }
        }
        w.gap(8f)
    }

    private fun cellText(nodes: List<LawNode>, level: LawLevel): CharSequence {
        val sb = SpannableStringBuilder()
        nodes.forEachIndexed { i, n ->
            if (i > 0) sb.append("\n\n")
            val st = sb.length
            sb.append(n.displayHeading.ifBlank { n.sourceName })
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // 감독규정·시행세칙은 여러 문서가 섞이므로 문서 이름을 밝힌다
            if (level.ordinal >= LawLevel.SUPERVISORY_REGULATION.ordinal) {
                sb.append("\n")
                val s2 = sb.length
                sb.append(n.sourceName)
                sb.setSpan(ForegroundColorSpan(Color.GRAY), s2, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("\n").append(n.article.body)
        }
        return sb
    }
}
