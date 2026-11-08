package io.github.hdlee73.financenewsradar.accounting.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 외부 브라우저로 열기. 열 앱이 없어도 앱이 종료되지 않게 막는다. */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "링크를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, shape = shape) { Box(Modifier.padding(14.dp)) { content() } }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, shape = shape) { Box(Modifier.padding(14.dp)) { content() } }
    }
}

@Composable
fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun FineTag(text: String) = Tag(text, MaterialTheme.colorScheme.errorContainerSafe(), MaterialTheme.colorScheme.error)

@Composable
fun InfoTag(text: String) = Tag(text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)

@Composable
fun WarnTag(text: String) = Tag(text, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)

private fun androidx.compose.material3.ColorScheme.errorContainerSafe(): Color = this.error.copy(alpha = 0.12f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) }) }
    }
}

/**
 * 화면 머리말. FSS Insights의 다른 화면과 같은 하늘색 히어로 머리말로 그린다.
 * 목록이 좌우 여백([SCREEN_SIDE_PADDING])을 contentPadding으로 두므로, 머리말만 그 여백을 건너 화면 끝까지 펼친다.
 */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    io.github.hdlee73.financenewsradar.ui.LargeTitle(
        title,
        subtitle = subtitle,
        modifier = Modifier.layout { measurable, constraints ->
            val extra = (SCREEN_SIDE_PADDING * 2).roundToPx()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.minWidth
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-SCREEN_SIDE_PADDING.roundToPx(), 0) }
        }
    )
}

/** 화면 좌우 여백. 목록은 `contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING)`로 준다. */
val SCREEN_SIDE_PADDING = 14.dp

/** 회사명 → DART 검색어: ㈜·(주)·주식회사와 '(現 …)' 같은 괄호 설명을 뗀다. 가려진 이름(○○○)이면 빈 문자열. */
fun dartSearchName(raw: String): String {
    val s = raw.replace(Regex("\\([^)]*\\)"), " ").replace("㈜", "").replace("주식회사", "").replace(Regex("\\s+"), " ").trim()
    return if (s.any { it.isLetterOrDigit() && it !in "○◯◌●□■◎☆★△▲◇◆" }) s else ""
}

/** 누르면 「기업 개황 및 공시(DART)」에서 이 회사를 찾는 회사명. 검색할 수 없는 이름(가려진 이름)은 그냥 글자로 둔다. */
@Composable
fun CompanyName(name: String, onOpenCompany: ((String) -> Unit)?, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall) {
    val query = dartSearchName(name)
    if (onOpenCompany == null || query.isEmpty()) {
        Text(name, style = style, modifier = modifier)
    } else {
        Text(
            name, style = style, color = MaterialTheme.colorScheme.primary,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = modifier.clickable(onClickLabel = "기업 개황 및 공시에서 찾기") { onOpenCompany(query) }
        )
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun KpiRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun RowScope.Kpi(value: String, label: String) {
    Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

const val DISCLAIMER = "※ 본 앱은 금융감독원·한국회계기준원 등과 무관한 비공식 참고 도구이며, 표시 내용은 공개 자료를 정리한 것으로 법적 효력이 없습니다. 정확한 내용은 반드시 원문을 확인하세요."
