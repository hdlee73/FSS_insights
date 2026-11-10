package io.github.hdlee73.financenewsradar.accounting.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.accounting.DataState
import io.github.hdlee73.financenewsradar.accounting.data.AuditAction
import io.github.hdlee73.financenewsradar.accounting.domain.Format
import io.github.hdlee73.financenewsradar.accounting.ui.AppCard
import io.github.hdlee73.financenewsradar.accounting.ui.CenterMessage
import io.github.hdlee73.financenewsradar.accounting.ui.ChipRow
import io.github.hdlee73.financenewsradar.accounting.ui.CaseRow
import io.github.hdlee73.financenewsradar.accounting.ui.dartSearchName
import io.github.hdlee73.financenewsradar.accounting.ui.DISCLAIMER
import io.github.hdlee73.financenewsradar.accounting.ui.FineTag
import io.github.hdlee73.financenewsradar.accounting.ui.InfoTag
import io.github.hdlee73.financenewsradar.accounting.ui.WarnTag
import io.github.hdlee73.financenewsradar.accounting.ui.LoadingBox
import io.github.hdlee73.financenewsradar.accounting.ui.SCREEN_SIDE_PADDING
import io.github.hdlee73.financenewsradar.accounting.ui.ScreenTitle
import io.github.hdlee73.financenewsradar.accounting.ui.Tag
import io.github.hdlee73.financenewsradar.accounting.ui.openUrl

private val MARKETS = listOf("전체", "코스닥", "유가증권", "코넥스", "비상장")
private val ACTION_FILTERS = listOf("전체", "과징금", "고발·통보", "감사인지정")

fun matchesAudit(a: AuditAction, market: String, filter: String, query: String): Boolean {
    if (market != "전체" && !a.market.contains(market)) return false
    val ok = when (filter) {
        "과징금" -> a.flags.fine
        "고발·통보" -> a.flags.prosecution || a.flags.referral
        "감사인지정" -> a.flags.auditorDesignationYears != null
        else -> true
    }
    if (!ok) return false
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val hay = buildString {
        append(a.company).append(' ').append(a.industry).append(' ').append(a.accounts.joinToString(" ")).append(' ').append(a.violationTypes.joinToString(" "))
        a.findings.forEach { append(' ').append(it.title) }
    }
    return terms.all { hay.contains(it, ignoreCase = true) }
}

@Composable
fun AuditScreen(state: DataState, selectedSlno: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier, onOpenCompany: ((String) -> Unit)? = null) {
    if (state.loading && state.audit.isEmpty()) { LoadingBox(); return }
    if (state.audit.isEmpty()) { CenterMessage(state.error ?: "표시할 감리조치 자료가 없습니다."); return }
    val selected = selectedSlno?.let { s -> state.audit.firstOrNull { it.key == s } }
    if (selected != null) { AuditDetail(selected, modifier, onOpenCompany); return }

    var query by rememberSaveable { mutableStateOf("") }
    var market by rememberSaveable { mutableStateOf("전체") }
    var filter by rememberSaveable { mutableStateOf("전체") }
    val list = remember(state.audit, query, market, filter) { state.audit.filter { matchesAudit(it, market, filter, query) } }

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("회계감리 조치현황", "금감원 공시 · 최근 3년 · 총 ${state.audit.size}건") }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("회사명·지적내용·계정·위반유형") }
            )
        }
        item { ChipRow(MARKETS, market) { market = it } }
        item { ChipRow(ACTION_FILTERS, filter) { filter = it } }
        if (list.isEmpty()) item { Text("조건에 맞는 조치가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
        items(list, key = { it.key }) { a -> AuditCard(a, onOpenCompany) { onSelect(a.key) } }
        item { Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditCard(a: AuditAction, onOpenCompany: ((String) -> Unit)?, onClick: () -> Unit) {
    AppCard {
        CaseRow(a.company, onOpenCompany, onClick) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ActionTags(a) }
            Text(a.company, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text("${Format.dateYmd(a.actionDate)} · ${a.market} · 감리대상 ${a.auditPeriod}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val first = a.findings.firstOrNull()?.title
            if (first != null) Text(
                if (a.findings.size > 1) "$first 외 ${a.findings.size - 1}건" else first,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp), maxLines = 2
            ) else Text("지적사항 자동 추출 실패 · 원문 확인", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditDetail(a: AuditAction, modifier: Modifier = Modifier, onOpenCompany: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle(a.company, "${Format.dateYmd(a.actionDate)} 조치 · ${a.market}" + (a.decisionRound?.let { " · 제${it}차 증선위" } ?: "")) }
        if (onOpenCompany != null && dartSearchName(a.company).isNotEmpty()) item {
            OutlinedButton(onClick = { onOpenCompany(dartSearchName(a.company)) }, modifier = Modifier.fillMaxWidth()) { Text("기업 개황 및 공시(DART)에서 ${a.company} 찾기") }
        }
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("감리대상 사업연도: ${a.auditPeriod}", style = MaterialTheme.typography.bodyMedium)
                    if (a.industry.isNotEmpty()) Text("업종: ${a.industry}", style = MaterialTheme.typography.bodyMedium)
                    if (a.fiscalYearEnds.isNotEmpty()) Text("결산기: ${a.fiscalYearEnds}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Text("조치 내용", style = MaterialTheme.typography.titleMedium) }
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.actions.isEmpty()) Text("조치 내용을 자동 추출하지 못했습니다. 원문을 확인하세요.", color = MaterialTheme.colorScheme.error)
                    a.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    if (a.flags.fineNote.isNotEmpty()) Text("※ ${a.flags.fineNote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("지적사항 ${a.findings.size}건", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(a.findings) { _, f ->
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${f.no}. ${f.title}", style = MaterialTheme.typography.titleSmall)
                    if (f.amountNote.isNotEmpty()) Text(f.amountNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (f.summary.isNotEmpty()) Text(f.summary, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        f.accounts.forEach { InfoTag(it) }
                        f.violations.forEach { Tag(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("출처: 금융감독원 회계감리결과제재 공시 / 직접 수집 (원문 링크 아래)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { openUrl(context, a.viewUrl) }, modifier = Modifier.fillMaxWidth()) { Text("금감원 공시 페이지 열기") }
                a.files.forEach { OutlinedButton(onClick = { openUrl(context, it.url) }, modifier = Modifier.fillMaxWidth()) { Text("첨부 원문: ${it.name}", maxLines = 1) } }
            }
        }
        item { Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
    }
}

@Composable
internal fun ActionTags(a: AuditAction) {
    val f = a.flags
    if (f.fine) FineTag(if (f.fineFinal && f.fineWon != null) "과징금 ${Format.won(f.fineWon)}" else "과징금(금융위 결정 예정)")
    f.auditorDesignationYears?.let { WarnTag("감사인지정 ${it}년") }
    if (f.prosecution) InfoTag("고발") else if (f.referral) InfoTag("통보")
}
