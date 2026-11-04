package io.github.hdlee73.financenewsradar.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 외부 라이브러리 없이 엑셀(.xlsx) 파일을 만든다(가볍게 유지하려고 필요한 최소 구조만 쓴다).
 * 첫 줄은 굵은 머리글로 고정하고, 숫자는 숫자 칸(천 단위 쉼표)으로, 나머지는 글자 칸으로 넣는다.
 */
object XlsxWriter {
    data class Sheet(val name: String, val header: List<String>, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(sheetName(s.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            })
            // 0: 기본, 1: 굵은 머리글(옅은 배경), 2: 천 단위 쉼표 숫자, 3: 줄바꿈 글자
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="10"/><name val="맑은 고딕"/></font><font><b/><sz val="10"/><name val="맑은 고딕"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8ECFB"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="4"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/><xf numFmtId="3" applyNumberFormat="1"/><xf applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun sheetXml(s: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (s.widths.isNotEmpty()) {
            append("<cols>")
            s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
            append("</cols>")
        }
        append("<sheetData>")
        row(this, 1, s.header, header = true)
        s.rows.forEachIndexed { i, r -> row(this, i + 2, r, header = false) }
        append("</sheetData>")
        if (s.rows.isNotEmpty()) append("""<autoFilter ref="A1:${col(s.header.size - 1)}${s.rows.size + 1}"/>""")
        append("</worksheet>")
    }

    private fun row(sb: StringBuilder, n: Int, cells: List<Any?>, header: Boolean) {
        sb.append("""<row r="$n">""")
        cells.forEachIndexed { i, v ->
            val ref = "${col(i)}$n"
            when {
                v == null -> {}
                !header && v is Number -> sb.append("""<c r="$ref" s="2"><v>$v</v></c>""")
                else -> {
                    val text = v.toString()
                    val style = if (header) 1 else if (text.contains('\n')) 3 else 0
                    sb.append("""<c r="$ref" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(text)}</t></is></c>""")
                }
            }
        }
        sb.append("</row>")
    }

    internal fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun sheetName(name: String) = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31)

    /** XML에 넣을 수 없는 제어 문자는 버리고 특수 문자는 바꾼다. */
    internal fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch < ' ' && ch != '\n' && ch != '\t' -> {}
            else -> append(ch)
        }
    }
}
