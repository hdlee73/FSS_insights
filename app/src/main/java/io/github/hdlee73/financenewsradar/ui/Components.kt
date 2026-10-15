package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면 맨 위 히어로 머리말: 하늘색→파랑 그라데이션 위에 큰 제목과 한 줄 설명을 얹는다(스크롤하면 목록과 함께 올라간다).
 * 위 메뉴 바와 같은 색에서 시작해 자연스럽게 이어진다. 오른쪽에는 아이콘 동작을 둔다.
 */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
    Box(
        modifier = modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(AppColors.heroStart, AppColors.heroEnd)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.16f), radius = size.height * 0.95f, center = Offset(size.width * 0.96f, -size.height * 0.15f))
                drawCircle(Color.White.copy(alpha = 0.08f), radius = size.height * 0.5f, center = Offset(size.width * 0.72f, size.height * 1.05f))
            }
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero, maxLines = 2)
                if (subtitle != null) {
                    Text(subtitle, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = AppColors.onHero.copy(alpha = 0.88f), maxLines = 2)
                }
            }
            CompositionLocalProvider(LocalContentColor provides AppColors.onHero) { trailing() }
        }
    }
}

/** 뒤로가기가 있는 머리말(같은 그라데이션). */
@Composable
internal fun BackHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(AppColors.heroStart, AppColors.heroEnd))).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = AppColors.onHero) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = AppColors.onHero, maxLines = 1)
        CompositionLocalProvider(LocalContentColor provides AppColors.onHero) { trailing() }
    }
}

/** 목록 한 건을 담는 둥근 카드. [stripe]를 주면 왼쪽에 색띠가 붙는다. */
@Composable
fun ListCard(modifier: Modifier = Modifier, stripe: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Row(click.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            if (stripe != null) Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
        }
    }
}

/** 회사명 첫 글자를 담은 둥근 아바타. [tint] 색의 옅은 바탕에 같은 색 글자. */
@Composable
fun InitialAvatar(name: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).background(tint.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().firstOrNull()?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
    }
}

/** 아이콘을 담은 둥근 사각 타일. */
@Composable
fun IconTile(tint: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.size(44.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { content() }
}

/** 회색 띠(구역 사이 간격). 클리앙처럼 블록을 띠로 나눈다. */
@Composable
fun SectionBand(height: Dp = 10.dp) {
    Box(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant))
}

/** 블록 제목 + 오른쪽 화살표(›). [accent]가 true면 주황색. */
@Composable
fun BlockHeader(title: String, modifier: Modifier = Modifier, accent: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (accent) AppColors.accent else MaterialTheme.colorScheme.secondary,
            maxLines = 1
        )
        trailing()
    }
}

/** 제목 앞에 붙는 작은 말머리 라벨(예: 검사, 보도자료). */
@Composable
fun TagLabel(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 선택한 항목 아래에 굵은 밑줄이 붙는 글자 탭. */
@Composable
fun UnderlineTabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.onBackground
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val underline = if (selected) AppColors.accent else androidx.compose.ui.graphics.Color.Transparent
                Text(
                    label,
                    modifier = Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                        .drawBehind {
                            drawRect(color = underline, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
                        }
                        .padding(top = 12.dp, bottom = 11.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) active else inactive,
                    maxLines = 1
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 둥근 흰색 검색창. */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = "검색어",
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
    outlined: Boolean = true
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp).semantics { contentDescription = description },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        inner()
                    }
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "입력 지우기", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        )
    }
}

/** 둥근 알약 모양 칩(키워드·필터). */
@Composable
fun PillChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onPanel: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/** 목록 위의 작은 구역 제목. 오른쪽에 동작 하나를 둘 수 있다. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        action?.invoke(this)
    }
}

@Composable
fun RowDivider(inset: Dp = 20.dp) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = inset), color = MaterialTheme.colorScheme.outlineVariant)
}

/** 새로 올라온 자료 표시. */
@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            "NEW",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 비었을 때·오류일 때 가운데에 보여 주는 안내와 선택 버튼. */
@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, isError: Boolean = false, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** 목록을 조금 내려가면 오른쪽 아래에 나타나는 "맨 위로" 버튼(모든 탭 공통 모양). [Box] 안에서 목록 위에 겹쳐 둔다. */
@Composable
fun BoxScope.ScrollToTopButton(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onBeforeScroll: () -> Unit = {}
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val visible by remember { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 120 } }
    if (visible) {
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = {
                onBeforeScroll()
                scope.launch { state.animateScrollToItem(0) }
            },
            modifier = modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.VerticalAlignTop, contentDescription = "맨 위로 이동")
        }
    }
}
