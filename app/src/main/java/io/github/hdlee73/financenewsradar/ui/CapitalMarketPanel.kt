package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hdlee73.financenewsradar.data.KofiaGroup
import io.github.hdlee73.financenewsradar.data.KofiaItem
import java.util.Locale
import kotlin.math.abs

/** 금융투자협회 "한눈에 보는 자본시장통계" 중 펀드·자금동향. 국내 금융 통계와 같은 수준의 항목이다. */
@Composable
internal fun CapitalMarketPanel(all: List<KofiaGroup>, hidden: Set<String>, loading: Boolean, failed: Boolean, onPick: () -> Unit) {
    // 숨긴 항목을 빼고, 항목이 모두 빠진 소제목·구분은 통째로 뺀다.
    val groups = all.mapNotNull { g ->
        val sections = g.sections.mapNotNull { sec -> sec.copy(items = sec.items.filter { it.key !in hidden }).takeIf { it.items.isNotEmpty() } }
        if (sections.isEmpty()) null else g.copy(sections = sections)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        SubHeading("자본시장 통계") { if (all.isNotEmpty()) SoftButton(onClick = onPick) { Text("항목 선택", fontSize = 12.sp) } }
        val shape = RoundedCornerShape(10.dp)
        if (groups.isEmpty()) {
            Text(
                if (loading && all.isEmpty()) "자본시장 통계를 불러오는 중입니다…" else if (all.isNotEmpty()) "표시할 항목이 없습니다. 오른쪽 위 '항목 선택'에서 고르세요." else if (failed) "자본시장 통계를 불러오지 못했습니다. 새로고침으로 다시 시도해 주세요." else "",
                Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape).clip(shape)) {
            groups.forEach { group ->
                Text(
                    group.title,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                group.sections.forEach { section ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            section.title + if (section.asOf.isNotEmpty()) " (${section.asOf})" else "",
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary
                        )
                        Text(section.unit, style = TextStyle(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    section.items.forEach { item ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        KofiaRow(item)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Text(
            "금융투자협회 종합통계 포털 기준. 직전 영업일(신규펀드는 직전 조회일) 대비 증감.",
            Modifier.padding(start = 2.dp, top = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KofiaRow(item: KofiaItem) {
    val color = changeColor(item.change)
    val arrow = when { (item.change ?: 0.0) > 0 -> "▲"; (item.change ?: 0.0) < 0 -> "▼"; else -> "" }
    val delta = item.change?.let { arrow + String.format(Locale.KOREA, "%,.0f", abs(it)) } ?: item.changeText
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(item.name, Modifier.weight(1f).padding(end = 8.dp), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.value, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp))
            Text(delta, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium), color = color)
        }
    }
}

/** 자본시장 통계 항목 선택: 소제목별로 항목마다 체크해 보일 항목을 고른다(국내 금융 통계 선택과 같은 방식). */
@Composable
internal fun KofiaPickerDialog(all: List<KofiaGroup>, hidden: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var hide by remember { mutableStateOf(hidden) }
    fun toggle(key: String) { hide = if (key in hide) hide - key else hide + key }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("표시할 항목 선택") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                all.forEach { group ->
                    group.sections.forEach { section ->
                        item(key = "s-${group.title}-${section.title}") {
                            Text(
                                "${group.title} · ${section.title}", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold
                            )
                        }
                        items(section.items, key = { it.key }) { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Checkbox(item.key !in hide, { toggle(item.key) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(hide) }) { Text("저장") } },
        dismissButton = {
            Row {
                TextButton(onClick = { hide = emptySet() }) { Text("기본값") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}
