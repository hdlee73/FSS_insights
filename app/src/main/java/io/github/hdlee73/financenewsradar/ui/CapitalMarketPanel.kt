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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
internal fun CapitalMarketPanel(groups: List<KofiaGroup>, loading: Boolean, failed: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        SubHeading("자본시장 통계")
        val shape = RoundedCornerShape(10.dp)
        if (groups.isEmpty()) {
            Text(
                if (loading) "자본시장 통계를 불러오는 중입니다…" else if (failed) "자본시장 통계를 불러오지 못했습니다. 새로고침으로 다시 시도해 주세요." else "",
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
