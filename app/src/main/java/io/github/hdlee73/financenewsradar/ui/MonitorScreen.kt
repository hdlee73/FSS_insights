package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.data.KeywordTrend
import io.github.hdlee73.financenewsradar.ui.theme.AppColors

/** 맞춤 키워드별 최근 7일 기사 수 추이와 급증 표시. 키워드를 누르면 그 키워드로 검색한다. */
@Composable
fun MonitorScreen(viewModel: NewsViewModel, onBack: () -> Unit, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trends = state.trends.sortedWith(compareByDescending<KeywordTrend> { it.isSurge }.thenByDescending { it.today })

    Column(modifier.fillMaxSize()) {
        BackHeader("키워드 모니터링", onBack) {
            IconButton(onClick = viewModel::loadTrends) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "info") {
                Text(
                    "내 키워드 ${state.settings.keywords.size}개의 최근 7일 기사 수입니다. 오늘 기사가 5건 이상이면서 지난 6일 평균의 2배 이상이면 ‘급증’으로 표시합니다. 키워드를 누르면 기사를 검색합니다. 새 기사 알림은 더보기 탭의 ‘알림’에서 키워드별로 설정합니다.",
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SectionBand(6.dp)
            }
            if (state.trendsLoading && trends.isEmpty()) item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            } else if (trends.isEmpty()) item(key = "empty") {
                CenterMessage("추이를 불러오지 못했습니다.", actionLabel = "다시 시도", onAction = viewModel::loadTrends)
            } else {
                items(trends, key = { it.keyword }) { trend ->
                    TrendRow(trend, onClick = { onPick(trend.keyword) })
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (trends.any { it.capped }) item(key = "note") {
                    Text(
                        "※ 검색은 키워드당 최근 100건까지만 가져오므로, 기사가 아주 많은 키워드는 오래된 날의 수가 실제보다 적게 보일 수 있습니다.",
                        Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendRow(trend: KeywordTrend, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(trend.keyword, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (trend.isSurge) TagLabel("급증", accent = true)
            }
            Text(
                if (trend.error != null) "불러오지 못함 (${trend.error.take(40)})"
                else "오늘 ${trend.today}건 · 6일 평균 ${"%.1f".format(trend.previousAverage)}건 · 7일 ${trend.total}건",
                style = MaterialTheme.typography.bodySmall,
                color = if (trend.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spark(trend.daily, trend.isSurge, Modifier.width(92.dp).height(36.dp))
    }
}

@Composable
private fun Spark(daily: List<Int>, surge: Boolean, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.outlineVariant.let { if (it.luminance() > 0.5f) Color(0xFFD5D9E0) else Color(0xFF3A414C) }
    val last = if (surge) AppColors.accent else MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val max = (daily.maxOrNull() ?: 1).coerceAtLeast(1)
        val n = daily.size.coerceAtLeast(1)
        val barWidth = size.width / (2 * n - 1)
        val minHeight = 2.dp.toPx()
        daily.forEachIndexed { index, value ->
            val h = (size.height * value / max).coerceAtLeast(minHeight)
            drawRoundRect(
                color = if (index == n - 1) last else base,
                topLeft = Offset(index * 2 * barWidth, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
