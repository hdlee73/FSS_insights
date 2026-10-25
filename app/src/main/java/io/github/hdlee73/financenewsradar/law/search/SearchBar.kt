package io.github.hdlee73.financenewsradar.law.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import io.github.hdlee73.financenewsradar.law.EverylawIcons
import io.github.hdlee73.financenewsradar.law.Gold
import io.github.hdlee73.financenewsradar.law.Navy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hdlee73.everylaw.core.model.formatYmd
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 검색창 + 조회 버튼 + 시행일 칩.
 * "법령명 제N조"는 위임 트리로, 조문 번호가 없는 단어는 키워드 통합 검색으로 처리된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LawSearchBar(
    query: String,
    asOf: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAsOfChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    showHeadline: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val focus = LocalFocusManager.current
    var showPicker by remember { mutableStateOf(false) }

    // 남색 머리띠: 상태 표시줄 아래까지 칠하고, 아래쪽 모서리만 둥글게
    Column(
        modifier
            .fillMaxWidth()
            .background(Navy, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).background(Gold, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(EverylawIcons.Stairs, contentDescription = null, tint = Navy, modifier = Modifier.size(18.dp))
            }
            Text("스마트 법령검색", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            Surface(
                onClick = { showPicker = true },
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Gold.copy(alpha = 0.5f)),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (asOf == null) "현행 기준" else "${formatYmd(asOf)} 기준", style = MaterialTheme.typography.labelMedium, color = Gold)
                }
            }
        }
        if (showHeadline) {
            Text(
                "어떤 조문의\n위임 구조를 볼까요?",
                style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 31.sp),
                color = Color.White,
            )
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("예) 자본시장법 제12조 제2항", maxLines = 1, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)) },
            leadingIcon = { Icon(EverylawIcons.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "검색어 지우기")
                        }
                    }
                    FilledIconButton(
                        onClick = { focus.clearFocus(); onSearch() },
                        modifier = Modifier.padding(end = 6.dp).size(40.dp),
                        shape = RoundedCornerShape(9.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gold, contentColor = Navy),
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "조회") }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 26.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            ),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedTextColor = Navy,
                unfocusedTextColor = Navy,
                cursorColor = Navy,
                focusedPlaceholderColor = Color(0xFF56627A),
                unfocusedPlaceholderColor = Color(0xFF56627A),
                focusedLeadingIconColor = Navy,
                unfocusedLeadingIconColor = Navy,
                focusedTrailingIconColor = Navy,
                unfocusedTrailingIconColor = Navy,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
        )
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = asOf?.let {
                LocalDate.parse(it, YMD).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            } ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        onAsOfChange(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().format(YMD))
                    }
                    showPicker = false
                }) { Text("이 날짜 기준") }
            },
            dismissButton = {
                Row {
                    if (asOf != null) TextButton(onClick = { onAsOfChange(null); showPicker = false }) { Text("현행으로") }
                    TextButton(onClick = { showPicker = false }) { Text("취소") }
                }
            },
        ) {
            DatePicker(state = pickerState, title = { Text("조회 기준 시행일", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

private val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
