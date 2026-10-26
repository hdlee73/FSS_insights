package io.github.hdlee73.financenewsradar.law.tree

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hdlee73.everylaw.core.domain.CompareRow
import com.hdlee73.everylaw.core.domain.CompareTable
import com.hdlee73.everylaw.core.model.LawLevel
import com.hdlee73.everylaw.core.model.LawNode
import io.github.hdlee73.financenewsradar.law.LevelColors

/**
 * 법령 비교표를 앱 화면에서 본다. 법률은 항상 첫 열이고, 칩으로 시행령·시행규칙·감독규정·시행세칙을 골라 열을 바꾼다.
 * 하위 규정은 PDF 비교표와 같이 인용된 항·호와 같은 줄에서 시작한다. 뒤로가기는 비교표만 닫는다.
 */
@Composable
fun CompareDialog(root: LawNode, title: String, onDismiss: () -> Unit, onPdf: (Set<LawLevel>) -> Unit) {
    val available = remember(root) { root.children.flatMap { it.flatten() }.map { it.level }.toSet() }
    var chosen by remember(root) { mutableStateOf(available.ifEmpty { setOf(LawLevel.DECREE) }.filter { it != LawLevel.LAW }.toSet()) }
    val rows = remember(root, chosen) { CompareTable.build(root, chosen) }
    val levels = remember(chosen) { CompareTable.columns(chosen) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") }
                    Text("$title 비교표", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    IconButton(onClick = { onPdf(chosen) }) { Icon(Icons.Outlined.PictureAsPdf, "PDF 저장") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = true, onClick = {}, enabled = false, label = { Text("법률(고정)") })
                    LawLevel.entries.filter { it != LawLevel.LAW }.forEach { lv ->
                        FilterChip(
                            selected = lv in chosen,
                            onClick = { chosen = if (lv in chosen) chosen - lv else chosen + lv },
                            label = { Text(lv.label) },
                        )
                    }
                }
                val colW = 280.dp
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    Column(Modifier.horizontalScroll(scroll)) {
                        Row {
                            levels.forEach { lv ->
                                Box(
                                    Modifier.width(colW).background(LevelColors.current[lv].accent).padding(horizontal = 8.dp, vertical = 6.dp),
                                ) { Text(lv.label, color = LevelColors.current[lv].onAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                            }
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(rows.size) { i -> CompareRowView(rows[i], levels, colW) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareRowView(row: CompareRow, levels: List<LawLevel>, colW: androidx.compose.ui.unit.Dp) {
    val border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp)
    // 법률 칸에서 위임받은 항·호가 시작하는 줄 앞의 줄 수만큼 하위 규정 칸 앞에 빈 줄을 둬 같은 줄에서 시작하게 한다.
    var lead by remember(row) { mutableIntStateOf(0) }
    Row(Modifier.height(IntrinsicSize.Max)) {
        levels.forEachIndexed { i, lv ->
            Box(Modifier.width(colW).fillMaxHeight().border(border).padding(8.dp)) {
                if (i == 0) {
                    Text(row.lawText, style = style, onTextLayout = { r ->
                        if (row.cells.isNotEmpty() && row.lawText.isNotEmpty()) lead = r.getLineForOffset(row.anchorStart.coerceIn(0, row.lawText.length - 1))
                    })
                } else {
                    val nodes = row.cells[lv].orEmpty()
                    if (nodes.isNotEmpty()) Text(cellText(nodes, lv, lead), style = style)
                }
            }
        }
    }
}

private fun cellText(nodes: List<LawNode>, level: LawLevel, lead: Int): AnnotatedString = buildAnnotatedString {
    append("\n".repeat(lead))
    nodes.forEachIndexed { i, n ->
        if (i > 0) append("\n\n")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(n.displayHeading.ifBlank { n.sourceName }) }
        if (level.ordinal >= LawLevel.SUPERVISORY_REGULATION.ordinal) append("\n" + n.sourceName)
        append("\n" + n.article.body)
    }
}
