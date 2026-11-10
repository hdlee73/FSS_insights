package io.github.hdlee73.financenewsradar.accounting.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.accounting.DataState
import io.github.hdlee73.financenewsradar.accounting.data.BoardItem
import io.github.hdlee73.financenewsradar.accounting.data.StandardItem
import io.github.hdlee73.financenewsradar.accounting.ui.AppCard
import io.github.hdlee73.financenewsradar.accounting.ui.CenterMessage
import io.github.hdlee73.financenewsradar.accounting.ui.ChipRow
import io.github.hdlee73.financenewsradar.accounting.ui.DISCLAIMER
import io.github.hdlee73.financenewsradar.accounting.ui.InfoTag
import io.github.hdlee73.financenewsradar.accounting.ui.LoadingBox
import io.github.hdlee73.financenewsradar.accounting.ui.SCREEN_SIDE_PADDING
import io.github.hdlee73.financenewsradar.accounting.ui.ScreenTitle
import io.github.hdlee73.financenewsradar.accounting.ui.openUrl

private const val ALL = "통합"
private val KINDS = listOf(ALL, "기준서", "질의회신", "해석", "감리지적사례")
private const val MAX_SHOWN = 100
private const val PER_KIND = 20

/** 기준서 묶음 표시 순서. 목록에 없는 묶음은 맨 뒤. */
val GROUP_ORDER = listOf("K-IFRS", "일반기업회계기준", "기타 기준서", "회계감사기준", "내부회계관리제도")
fun groupRank(g: String): Int = GROUP_ORDER.indexOf(g).let { if (it < 0) GROUP_ORDER.size else it }

private fun kindKey(label: String) = when (label) { "질의회신" -> "qna"; "해석" -> "interp"; "감리지적사례" -> "audit-case"; else -> "" }

fun matchesStandard(b: StandardItem, group: String, query: String): Boolean {
    if (group.isNotEmpty() && b.group != group) return false
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hay = b.group + " " + b.label + " " + b.no + " " + b.title
    return terms.all { hay.contains(it, ignoreCase = true) }
}

fun matchesBoard(b: BoardItem, kind: String, query: String): Boolean {
    if (kind.isNotEmpty() && b.kind != kind) return false
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val hay = b.title + " " + b.meta.joinToString(" ")
    return terms.all { hay.contains(it, ignoreCase = true) }
}

@Composable
fun SearchScreen(state: DataState, modifier: Modifier = Modifier) {
    if (state.loading && state.board.isEmpty() && state.standards.isEmpty()) { LoadingBox(); return }
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ALL) }
    var group by rememberSaveable { mutableStateOf("전체") }
    val isAll = kind == ALL
    val isStandard = kind == "기준서"
    val groups = remember(state.standards) { listOf("전체") + state.standards.map { it.group }.distinct().sortedBy { groupRank(it) } }
    val standards = remember(state.standards, query, group) { state.standards.filter { matchesStandard(it, if (group == "전체") "" else group, query) }.sortedBy { groupRank(it.group) } }
    val result = remember(state.board, query, kind) { if (isStandard || isAll) emptyList() else state.board.filter { matchesBoard(it, kindKey(kind), query) } }
    val hasQuery = query.isNotBlank()
    val allStd = remember(state.standards, query) { if (isAll && hasQuery) state.standards.filter { matchesStandard(it, "", query) }.sortedBy { groupRank(it.group) } else emptyList() }
    val allBoard = remember(state.board, query, kind) {
        if (isAll && hasQuery) listOf("qna", "interp", "audit-case").map { k -> k to state.board.filter { matchesBoard(it, k, query) } } else emptyList()
    }

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("회계기준서·질의회신·지적사례") }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isAll) "기준서·질의회신·해석·감리지적사례 통합 검색 (예: 리스)" else if (isStandard) "기준서 번호·제목 (예: 1116, 리스)" else "제목 키워드 (예: 리스, 특수관계자 주석)") })
        }
        item { ChipRow(KINDS, kind) { kind = it } }
        if (isAll) {
            if (!hasQuery) {
                item { CenterMessage("검색어를 입력하면 기준서·질의회신·해석·감리지적사례를 한 번에 찾습니다.") }
            } else {
                item { Text("기준서 ${allStd.size}건" + if (allStd.size > PER_KIND) " (상위 ${PER_KIND}건 표시)" else "", style = MaterialTheme.typography.labelLarge) }
                items(allStd.take(PER_KIND), key = { "std-" + it.group + it.no }) { b ->
                    AppCard(onClick = { openUrl(context, b.url) }) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            InfoTag("기준서 · ${b.group}")
                            Text(if (b.label.isNotEmpty() && !b.title.startsWith(b.label)) "${b.label} ${b.title}" else b.title, style = MaterialTheme.typography.titleSmall)
                            Text("출처: ${b.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                for ((k, list) in allBoard) {
                    val label = when (k) { "qna" -> "질의회신"; "interp" -> "해석"; else -> "감리지적사례" }
                    item(key = "hdr-$k") { Text("$label ${list.size}건" + if (list.size > PER_KIND) " (상위 ${PER_KIND}건 표시, 해당 탭에서 전체 보기)" else "", style = MaterialTheme.typography.labelLarge) }
                    items(list.take(PER_KIND), key = { "all-${it.kind}-${it.id}" }) { b ->
                        AppCard(onClick = { openUrl(context, b.url) }) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                InfoTag(b.kindLabel)
                                Text(b.title, style = MaterialTheme.typography.titleSmall)
                                Text((listOf(b.date) + b.meta).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (b.source.isNotEmpty()) Text("출처: ${b.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Text("제목·분류만 색인합니다. 항목의 출처와 원문 링크에서 본문을 확인하세요.\n$DISCLAIMER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
            }
        } else if (isStandard) {
            item { ChipRow(groups, group) { group = it } }
            item { Text("기준서 ${standards.size}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (standards.isEmpty()) item { CenterMessage("조건에 맞는 기준서가 없습니다.") }
            items(standards, key = { it.group + it.no }) { b ->
                AppCard(onClick = { openUrl(context, b.url) }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoTag(b.group)
                        Text(if (b.label.isNotEmpty() && !b.title.startsWith(b.label)) "${b.label} ${b.title}" else b.title, style = MaterialTheme.typography.titleSmall)
                        Text("출처: ${b.source} · 눌러서 원문 보기", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { openUrl(context, "https://db.kasb.or.kr/standard") }, modifier = Modifier.fillMaxWidth()) { Text("한국회계기준원 기준서 DB에서 전문 보기") }
            }
            item { Text("기준서 목록은 한국회계기준원 등 제정기관의 기준서를 회계위키(accountingwiki.co.kr)를 통해 정리한 것이며, 항목을 누르면 회계위키의 해당 기준서 페이지가 열립니다. 기타 기준서(중소기업·비영리조직·특수분야·지속가능성 공시기준 등)는 한국회계기준원 누리집의 해당 목록으로 연결됩니다. 원문 저작권은 각 제정기관에 있습니다.\n$DISCLAIMER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
        } else {
            if (state.board.isEmpty()) { item { CenterMessage(state.error ?: "표시할 자료가 없습니다.") } }
            item { Text("검색 결과 ${result.size}건" + if (result.size > MAX_SHOWN) " (상위 ${MAX_SHOWN}건 표시, 키워드를 더 입력해 좁혀 보세요)" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(result.take(MAX_SHOWN), key = { "${it.kind}-${it.id}" }) { b ->
                AppCard(onClick = { openUrl(context, b.url) }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoTag(b.kindLabel)
                        Text(b.title, style = MaterialTheme.typography.titleSmall)
                        Text((listOf(b.date) + b.meta).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (b.source.isNotEmpty()) Text("출처: ${b.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("제목·분류만 색인합니다. 항목에 표시한 출처(원본 기관 / 수집 경로)와 원문 링크에서 본문을 확인하세요.\n$DISCLAIMER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
        }
    }
}
