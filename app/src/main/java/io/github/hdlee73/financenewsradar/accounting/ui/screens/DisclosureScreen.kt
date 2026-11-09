package io.github.hdlee73.financenewsradar.accounting.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.accounting.DataState
import io.github.hdlee73.financenewsradar.accounting.data.DisclosureCase
import io.github.hdlee73.financenewsradar.accounting.domain.Format
import io.github.hdlee73.financenewsradar.accounting.ui.AppCard
import io.github.hdlee73.financenewsradar.accounting.ui.CenterMessage
import io.github.hdlee73.financenewsradar.accounting.ui.ChipRow
import io.github.hdlee73.financenewsradar.accounting.ui.CaseItemLayout
import io.github.hdlee73.financenewsradar.accounting.ui.DISCLAIMER
import io.github.hdlee73.financenewsradar.accounting.ui.FineTag
import io.github.hdlee73.financenewsradar.accounting.ui.InfoTag
import io.github.hdlee73.financenewsradar.accounting.ui.LoadingBox
import io.github.hdlee73.financenewsradar.accounting.ui.SCREEN_SIDE_PADDING
import io.github.hdlee73.financenewsradar.accounting.ui.ScreenTitle
import io.github.hdlee73.financenewsradar.accounting.ui.WarnTag
import io.github.hdlee73.financenewsradar.accounting.ui.dartSearchName
import io.github.hdlee73.financenewsradar.accounting.ui.openUrl

private val ACTION_FILTERS = listOf("전체", "과징금", "증권발행제한", "경고·주의", "고발·통보")

fun matchesDisclosure(c: DisclosureCase, year: String, filter: String, query: String): Boolean {
    if (year != "전체" && c.year != year) return false
    val ok = when (filter) {
        "과징금" -> c.fineWon != null || "과징금" in c.kinds
        "증권발행제한" -> c.issueLimitMonths != null || "증권발행제한" in c.kinds
        "경고·주의" -> c.kinds.any { it == "경고" || it == "주의" }
        "고발·통보" -> c.kinds.any { it.contains("고발") || it.contains("통보") }
        else -> true
    }
    if (!ok) return false
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val hay = buildString {
        append(c.company).append(' ').append(c.role).append(' ').append(c.actions.joinToString(" ")).append(' ').append(c.laws.joinToString(" "))
        c.violations.forEach { append(' ').append(it.title) }
    }
    return terms.all { hay.contains(it, ignoreCase = true) }
}

/**
 * 공시조사결과제재: 금감원 게시판(공시위반 조치 내역)의 2020년 이후 글을 첨부파일 세부내역까지 읽어 **회사별로** 보여 준다.
 * [onOpenCompany]는 회사명을 눌렀을 때 「기업 개황 및 공시(DART)」에서 그 회사를 찾는 동작, [onOpenLaw]는 위반 조문을 스마트 법령검색으로 여는 동작.
 */
@Composable
fun DisclosureScreen(
    state: DataState,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCompany: ((String) -> Unit)? = null,
    onOpenLaw: ((String) -> Unit)? = null
) {
    if (state.loading && state.disclosure.isEmpty()) { LoadingBox(); return }
    if (state.disclosure.isEmpty()) { CenterMessage(state.error ?: "표시할 공시조사결과제재 자료가 없습니다."); return }
    val selected = selectedId?.let { id -> state.disclosure.firstOrNull { it.id == id } }
    if (selected != null) { DisclosureDetail(selected, modifier, onOpenCompany, onOpenLaw); return }

    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("전체") }
    var filter by rememberSaveable { mutableStateOf("전체") }
    val years = remember(state.disclosure) { listOf("전체") + state.disclosure.map { it.year }.filter { it.isNotEmpty() }.distinct().sortedDescending() }
    val list = remember(state.disclosure, query, year, filter) { state.disclosure.filter { matchesDisclosure(it, year, filter, query) } }

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("공시조사결과제재", "증권선물위원회·금융위원회 공시위반 조치 · 2020년 이후 · 회사별 ${state.disclosure.size}건") }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("회사명·위반내용·조치·조문") }
            )
        }
        item { ChipRow(years, year) { year = it } }
        item { ChipRow(ACTION_FILTERS, filter) { filter = it } }
        item { Text("「기업개황 등」은 「기업 개황 및 공시(DART)」에서 그 회사를 찾고, 「세부내용」은 조치 상세를 엽니다. 한 첨부에 여러 회사가 있어도 회사별로 나누어 보여 줍니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (list.isEmpty()) item { Text("조건에 맞는 조치가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
        items(list, key = { it.id }) { c -> DisclosureCard(c, onOpenCompany) { onSelect(c.id) } }
        item { Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisclosureCard(c: DisclosureCase, onOpenCompany: ((String) -> Unit)?, onClick: () -> Unit) {
    AppCard {
        CaseItemLayout(c.company, onOpenCompany, onClick) {
          Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ActionTags(c) }
            Text(c.company, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                listOfNotNull(Format.dateYmd(c.date.replace("-", "")).takeIf { c.date.isNotEmpty() }, c.role.takeIf { it.isNotEmpty() }, c.committee.takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val first = c.violations.firstOrNull { it.title.isNotEmpty() }?.title
            if (first != null) Text(
                if (c.violations.size > 1) "$first 외 ${c.violations.size - 1}건" else first,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp), maxLines = 2
            )
          }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionTags(c: DisclosureCase) {
    if (c.fineWon != null) FineTag("과징금 ${Format.won(c.fineWon)}")
    c.issueLimitMonths?.let { WarnTag("증권발행제한 ${it}월") }
    c.kinds.filter { it != "과징금" && it != "증권발행제한" && it != "과태료" }.forEach { InfoTag(it) }
    if (c.fineWon == null && c.issueLimitMonths == null && c.kinds.isEmpty()) c.actions.take(1).forEach { InfoTag(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisclosureDetail(c: DisclosureCase, modifier: Modifier = Modifier, onOpenCompany: ((String) -> Unit)? = null, onOpenLaw: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle(c.company, listOfNotNull(c.date.takeIf { it.isNotEmpty() }?.let { "${Format.dateYmd(it.replace("-", ""))} 조치" }, c.role.takeIf { it.isNotEmpty() }).joinToString(" · ")) }
        if (onOpenCompany != null && dartSearchName(c.company).isNotEmpty()) item {
            OutlinedButton(onClick = { onOpenCompany(dartSearchName(c.company)) }, modifier = Modifier.fillMaxWidth()) { Text("기업 개황 및 공시(DART)에서 ${c.company} 찾기") }
        }
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (c.round.isNotEmpty()) Text(c.round, style = MaterialTheme.typography.bodyMedium)
                    Text("게시일 ${c.postDate}" + if (c.committee.isNotEmpty()) " · ${c.committee}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.coCount > 1) Text("같은 첨부에 ${c.coCount}개 회사가 함께 공시되었고, 이 화면은 이 회사 몫만 나누어 보여 줍니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.masked) Text("금감원이 공시에서 이름을 가려(○○○) 공개한 대상입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("조치 내용", style = MaterialTheme.typography.titleMedium) }
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (c.actions.isEmpty()) Text("조치 내용을 자동 추출하지 못했습니다. 원문을 확인하세요.", color = MaterialTheme.colorScheme.error)
                    c.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { Text("위반행위 ${c.violations.size}건", style = MaterialTheme.typography.titleMedium) }
        items(c.violations.size) { i ->
            val v = c.violations[i]
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${i + 1}. ${v.title.ifEmpty { "위반 내용" }}", style = MaterialTheme.typography.titleSmall)
                    v.facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (c.laws.isNotEmpty()) {
            item { Text("관련 법규", style = MaterialTheme.typography.titleMedium) }
            item {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.laws.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (onOpenLaw != null && c.lawRefs.isNotEmpty()) {
                            Text("스마트 법령검색으로 조문 열기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                c.lawRefs.forEach { ref ->
                                    Text(
                                        buildAnnotatedString {
                                            pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                                            append(ref.replace("자본시장과 금융투자업에 관한 법률", "자본시장법"))
                                            pop()
                                        },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable(onClickLabel = "스마트 법령검색에서 열기") { onOpenLaw(ref) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("출처: 금융감독원 공시조사결과제재(공시위반 조치 내역) / 직접 수집. 첨부 원문을 읽어 회사별로 정리했으며 원문과 다를 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.postUrl.isNotEmpty()) Button(onClick = { openUrl(context, c.postUrl) }, modifier = Modifier.fillMaxWidth()) { Text("금감원 게시글 열기") }
                if (c.fileUrl.isNotEmpty()) OutlinedButton(onClick = { openUrl(context, c.fileUrl) }, modifier = Modifier.fillMaxWidth()) { Text("첨부 원문: ${c.fileName}", maxLines = 1) }
            }
        }
        item { Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
    }
}
