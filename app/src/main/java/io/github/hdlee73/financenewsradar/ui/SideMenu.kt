package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.ui.theme.HeroPalettes
import kotlin.math.abs
import kotlin.math.sign

/** 왼쪽 메뉴의 한 줄: 화면 번호, 이름, 아이콘, 아이콘 타일 색. */
class SideMenuItem(val id: Int, val label: String, val icon: ImageVector, val tint: Color)

/** 왼쪽 메뉴의 묶음(소제목이 없으면 맨 위 묶음). */
class SideMenuSection(val title: String?, val items: List<SideMenuItem>)

/**
 * 아이폰 설정 앱처럼 둥근 카드 묶음에 색 아이콘 타일을 붙인 왼쪽 메뉴.
 * 메뉴는 본문 뒤에 깔려 있고, 열리면 본문이 오른쪽으로 밀려나며 작아진다(FinanceNewsRadarApp 참고).
 */
@Composable
fun SideMenu(
    sections: List<SideMenuSection>,
    selected: Int,
    badgeId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val card = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 20.dp)
    ) {
        Row(Modifier.padding(start = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(HeroPalettes.Sky.start, HeroPalettes.Sky.end), Offset.Zero, Offset(140f, 140f))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Insights, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Column(Modifier.padding(start = 12.dp)) {
                Text("FSS Insights", style = MaterialTheme.typography.titleLarge)
                Text("버전 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        sections.forEach { section ->
            if (section.title != null) {
                Text(
                    section.title,
                    Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(card)) {
                section.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SideMenuRow(item, selected = item.id == selected, badge = item.id == badgeId, onClick = { onSelect(item.id) })
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        Text(
            "만든이 이현덕 with Claude",
            Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SideMenuRow(item: SideMenuItem, selected: Boolean, badge: Boolean, onClick: () -> Unit) {
    val rowColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(rowColor).clickable(onClick = onClick)
            .height(48.dp).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(item.tint), contentAlignment = Alignment.Center) {
            Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(
            item.label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge) {
            Text(
                "NEW",
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary).padding(horizontal = 7.dp, vertical = 1.dp),
                color = MaterialTheme.colorScheme.onTertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "현재 화면", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 메뉴를 손가락으로 여닫는 가로 끌기. 자식보다 먼저(Initial) 이벤트를 보고, [canStart]가 허락한 곳에서
 * 가로로 끌 때만 이벤트를 가져간다. 세로로 움직이면 바로 손을 떼므로 목록 스크롤과 탭은 그대로 동작한다.
 */
suspend fun PointerInputScope.detectSideMenuDrag(
    canStart: (Offset) -> Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: (velocityX: Float) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!canStart(down.position)) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging) change.consume()
                break
            }
            val delta = change.positionChange()
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!dragging) {
                dx += delta.x
                dy += delta.y
                if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                if (abs(dx) > slop) {
                    dragging = true
                    onDrag(dx - sign(dx) * slop)
                    change.consume()
                }
            } else {
                onDrag(delta.x)
                change.consume()
            }
        }
        if (dragging) onDragEnd(tracker.calculateVelocity().x)
    }
}
