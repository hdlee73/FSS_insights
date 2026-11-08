package io.github.hdlee73.financenewsradar.accounting.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hdlee73.financenewsradar.accounting.SimulatorViewModel
import io.github.hdlee73.financenewsradar.accounting.data.SavedSim
import io.github.hdlee73.financenewsradar.accounting.domain.Actor
import io.github.hdlee73.financenewsradar.accounting.domain.ActorKind
import io.github.hdlee73.financenewsradar.accounting.domain.CompanyKind
import io.github.hdlee73.financenewsradar.accounting.domain.FineBlock
import io.github.hdlee73.financenewsradar.accounting.domain.FineModel
import io.github.hdlee73.financenewsradar.accounting.domain.Format
import io.github.hdlee73.financenewsradar.accounting.domain.Level
import io.github.hdlee73.financenewsradar.accounting.domain.Motive
import io.github.hdlee73.financenewsradar.accounting.domain.Reason
import io.github.hdlee73.financenewsradar.accounting.domain.Reasons
import io.github.hdlee73.financenewsradar.accounting.domain.ScaleBasis
import io.github.hdlee73.financenewsradar.accounting.domain.SimSettings
import io.github.hdlee73.financenewsradar.accounting.domain.Simulation
import io.github.hdlee73.financenewsradar.accounting.domain.VType
import io.github.hdlee73.financenewsradar.accounting.domain.Yangjeong
import io.github.hdlee73.financenewsradar.accounting.ui.AppCard
import io.github.hdlee73.financenewsradar.accounting.ui.ChipRow
import io.github.hdlee73.financenewsradar.accounting.ui.SCREEN_SIDE_PADDING
import io.github.hdlee73.financenewsradar.accounting.ui.ScreenTitle
import io.github.hdlee73.financenewsradar.ui.theme.AppColors

private val KINDS = CompanyKind.entries.map { it.label }
private val LEVELS = Level.entries.map { it.label }
private fun kindOf(l: String) = CompanyKind.entries.first { it.label == l }
private fun motiveOf(l: String) = Motive.entries.first { it.label == l }
private const val NONE = "해당없음"
private val MOTIVES = Motive.entries.map { it.label }
private val ASSIGN_OPTIONS = listOf(NONE) + MOTIVES
private fun levelOf(l: String) = Level.entries.first { it.label == l }

@Composable
fun SimulatorScreen(modifier: Modifier = Modifier, vm: SimulatorViewModel = viewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val stock by vm.stock.collectAsStateWithLifecycle()
    val st by vm.storeUi.collectAsStateWithLifecycle()
    val result = remember(s) { Simulation.run(s) }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> uri?.let(vm::exportTrading) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportFile) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }

    if (showSaved) SavedDialog(st.saved, onOpen = { vm.open(it); showSaved = false }, onDelete = { vm.delete(it.id) }, onDismiss = { showSaved = false })

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = SCREEN_SIDE_PADDING), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("과징금·양정 시뮬레이터", "외감규정·시행세칙 양정기준 적용 모의계산 (참고용)") }

        // ── 저장·불러오기
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = st.currentName, onValueChange = vm::setSimName, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("시뮬레이션 이름 (예: A사 2024 매출 과대계상)") }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::save, modifier = Modifier.weight(1f)) { Text("저장") }
                        OutlinedButton(onClick = { showSaved = true }, modifier = Modifier.weight(1f)) { Text("불러오기 (${st.saved.size})") }
                        TextButton(onClick = vm::reset) { Text("새로") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { exportLauncher.launch(vm.simFileName()) }) { Text("파일로 내보내기") }
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("파일 가져오기") }
                    }
                    st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text(
                        "🔒 시뮬레이션은 이 기기 안에만 저장되며 서버로 보내지 않습니다. 앱을 삭제하면 함께 지워지므로, 보관하거나 다른 기기·웹 버전에서 쓰려면 ‘파일로 내보내기’를 이용하세요.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 1. 회사
        item { SectionTitle("1. 회사 정보") }
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = stock.query, onValueChange = vm::setQuery, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text("회사명 (상장사: 주가·거래대금 조회)") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search() })
                        )
                        Button(onClick = vm::search, enabled = !stock.busy) { Text("조회") }
                    }
                    if (stock.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp); Text("조회 중…")
                    }
                    stock.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text("일평균거래금액 산정 기간 (기본: 직전 사업연도)", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateField("시작일", stock.from, vm::setFrom, Modifier.weight(1f))
                        Text("~")
                        DateField("종료일", stock.to, vm::setTo, Modifier.weight(1f))
                    }
                    if (stock.chosen != null || stock.summary != null) OutlinedButton(onClick = vm::recalc, enabled = !stock.busy, modifier = Modifier.fillMaxWidth()) { Text("이 기간으로 다시 계산") }
                    stock.candidates.forEach { c ->
                        OutlinedButton(onClick = { vm.choose(c) }, modifier = Modifier.fillMaxWidth()) { Text("${c.name}  (${c.code} · ${c.market})") }
                    }
                    stock.summary?.let { sm ->
                        HorizontalDivider()
                        Text("${sm.name} (${sm.code}) · ${sm.market}", style = MaterialTheme.typography.titleSmall)
                        Text("최근 종가 ${sm.lastClose?.let { "%,d원".format(it) } ?: "-"}" + (sm.lastDate?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                        Text("${sm.from} ~ ${sm.to} 일평균거래금액 ${Format.won(sm.avgTradingWon)}", style = MaterialTheme.typography.bodyMedium)
                        Text("거래일 ${sm.tradingDays}일 · 거래금액 합계 ${Format.won(sm.sumTradingWon)} (일별 종가×거래량 근사)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (stock.candles.isNotEmpty()) OutlinedButton(onClick = { vm.tradingFileName()?.let(xlsxLauncher::launch) }, modifier = Modifier.fillMaxWidth()) { Text("산정 내역 엑셀 저장 (일별 ${stock.candles.size}일)") }
                        val capm = FineModel.capm429(sm.avgTradingWon, true)
                        Text("자본시장법 제429조제3항 과징금 상한 추정: ${Format.won(capm)} (일평균거래금액 10%, 10억~20억원)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.update { it.copy(capmEok = "%.2f".format(capm / 1e8)) } }) { Text("이 금액을 자본시장법 병과액으로 사용") }
                    }
                    Text("회사 유형", style = MaterialTheme.typography.labelMedium)
                    ChipRow(KINDS, s.kind.label) { l -> vm.update { it.copy(kind = kindOf(l)) } }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("금융회사", Modifier.weight(1f)); Switch(s.financial, { v -> vm.update { it.copy(financial = v) } })
                    }
                    if (!s.kind.listed && !s.financial) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("소규모 비상장(규모조정계수 50% 적용 가능 요건)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(s.halfCoefficient, { v -> vm.update { it.copy(halfCoefficient = v) } })
                    }
                    NumField("대상 회계연도 말 자산총계 (억원, 완전자본잠식이면 부채총계)", s.assetEok) { v -> vm.update { it.copy(assetEok = v) } }
                    NumField("대상 회계연도 매출액 (억원)", s.salesEok) { v -> vm.update { it.copy(salesEok = v) } }
                    NumField("감사인의 중요성 금액 (억원, 선택 — 없으면 표준 중요성 기준 적용)", s.materialityEok) { v -> vm.update { it.copy(materialityEok = v) } }
                }
            }
        }

        // ── 2. 위법행위
        item { SectionTitle("2. 위법행위 (회계처리기준 위반)") }
        item { Text("위반행위마다 대상자별 위법 동기를 고르세요. 그 위반과 무관한 대상자는 ‘해당없음’으로 둡니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        s.rows.forEachIndexed { idx, r ->
            item(key = "row-${r.id}") {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("위반 ${idx + 1}" + (r.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (s.rows.size > 1) TextButton(onClick = { vm.removeRow(r.id) }) { Text("삭제") }
                        }
                        OutlinedTextField(
                            value = r.title, onValueChange = { v -> vm.updateRow(r.id) { it.copy(title = v.take(80)) } }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text("위반행위 제목 (예: 매출 과대계상, 재고자산 과대계상)") }
                        )
                        Text("유형", style = MaterialTheme.typography.labelMedium)
                        ChipRow(VType.entries.map { it.short }, r.type.short) { l ->
                            val t = VType.entries.first { it.short == l }
                            vm.updateRow(r.id) { it.copy(type = t, basis = t.defaultBasis) }
                        }
                        Text(r.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("규모금액 기준 (표1)", style = MaterialTheme.typography.labelMedium)
                        ChipRow(ScaleBasis.entries.map { it.label }, r.basis.label) { l -> vm.updateRow(r.id) { it.copy(basis = ScaleBasis.entries.first { b -> b.label == l }) } }
                        NumField("위반 금액 (억원)", r.amountEok) { v -> vm.updateRow(r.id) { it.copy(amountEok = v) } }
                        HorizontalDivider()
                        Text("대상자별 위법 동기", style = MaterialTheme.typography.labelMedium)
                        s.actors.sortedBy { it.kind.ordinal }.forEach { a ->
                            Text(a.title, style = MaterialTheme.typography.bodySmall)
                            ChipRow(ASSIGN_OPTIONS, r.assign[a.id]?.label ?: NONE) { l -> vm.setAssign(r.id, a.id, MOTIVES.indexOf(l).let { i -> if (i < 0) null else Motive.entries[i] }) }
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = vm::addRow, modifier = Modifier.fillMaxWidth()) { Text("+ 위법행위 추가") } }

        // ── 3. 대상자
        item { SectionTitle("3. 양정 대상자 (회사·임직원·감사인·공인회계사)") }
        item { Text("대상자마다 가중·감경 사유, 과징금 요소를 따로 정합니다. 임직원·감사인·공인회계사는 여러 명 추가할 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.addActor(ActorKind.OFFICER) }, modifier = Modifier.weight(1f)) { Text("+ 임직원") }
                OutlinedButton(onClick = { vm.addActor(ActorKind.AUDITOR) }, modifier = Modifier.weight(1f)) { Text("+ 감사인") }
                OutlinedButton(onClick = { vm.addActor(ActorKind.CPA) }, modifier = Modifier.weight(1f)) { Text("+ 회계사") }
            }
        }
        s.actors.sortedBy { it.kind.ordinal }.forEach { a ->
            item(key = "actor-${a.id}") { ActorCard(a, vm) }
        }
        item {
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("공통 과징금 요소 (별표 8)", style = MaterialTheme.typography.titleSmall)
                    Text("시장에 미치는 영향 (상: 다수 투자자 중대 손해·사회적 물의 / 중: 위반 재무제표로 증권 공모발행 피해)", style = MaterialTheme.typography.bodySmall)
                    ChipRow(LEVELS, s.marketImpact.label) { l -> vm.update { it.copy(marketImpact = levelOf(l)) } }
                    NumField("자본시장법 제429조 과징금 부과(예정)액 (억원, 선택 — 회사·감사인에게 차액만 부과/미부과 판단)", s.capmEok) { v -> vm.update { it.copy(capmEok = v) } }
                    NumField("위반 사업연도 수 (3개 초과 가중 계산용, 기본 1)", s.years.toString()) { v -> vm.update { it.copy(years = v.toIntOrNull()?.coerceIn(1, 10) ?: 1) } }
                }
            }
        }

        // ── 결과
        item { SectionTitle("모의계산 결과") }
        if (result.error != null) {
            item { Text(result.error, color = MaterialTheme.colorScheme.error) }
        } else {
            item { RowsCard(s) }
            item { ScaleCard(result.scaleLines) }
            result.actors.forEach { ar ->
                item(key = "res-${ar.actor.id}") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ar.assessment == null) AppCard { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${ar.actor.title} (${ar.actor.kind.label})", style = MaterialTheme.typography.titleSmall)
                            Text(ar.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                        else {
                            AssessmentCard("${if (ar.actor.title.contains(ar.actor.kind.label)) ar.actor.title else "${ar.actor.title} (${ar.actor.kind.label})"} 조치 · 위반 ${ar.rowCount}건", ar.assessment, ar.actions)
                            ar.fine?.let { FineCard(it) }
                            if (ar.fine == null && ar.note.isNotEmpty()) Text(ar.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            Text(
                "출처: 외부감사 및 회계 등에 관한 규정 [별표 8] 과징금 부과기준, 같은 규정 시행세칙 [별표 1] 심사·감리결과 조치양정기준(금융위원회·금융감독원 / 국가법령정보센터), 주가·거래대금은 네이버 증권.\n" +
                "※ 위 두 별표를 단순화해 적용한 비공식 모의계산입니다. " +
                    "가중·감경은 선택한 사유의 상한(최대치)을 가정하고, 사유가 겹치면 합산하되 100%를 넘지 않게 처리합니다. " +
                    "별표 8이 인용하는 '별표 7' 사유 번호는 현행 시행세칙 번호와 달라 내용 기준으로 연결했습니다. " +
                    "자본시장법 과징금은 별도 기준(자본시장조사 업무규정)이라 직접 입력한 금액만 반영합니다. " +
                    "실제 판단은 감리위원회·증권선물위원회·금융위원회가 하며 이 결과와 다를 수 있습니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { v -> if (v.all { it.isDigit() || it == '-' }) onChange(v) },
        label = { Text(label) }, placeholder = { Text("yyyy-MM-dd") }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun RowsCard(s: SimSettings) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("위법행위 내역", style = MaterialTheme.typography.titleSmall)
            s.rows.forEachIndexed { i, r ->
                val amt = Format.eokToWon(r.amountEok)
                Text(
                    "${i + 1}. ${r.title.ifBlank { "(제목 없음)" }} — ${r.type.short} · ${r.basis.label} 기준 · ${if (amt != null && amt > 0) Format.won(amt) else "금액 미입력"}",
                    style = MaterialTheme.typography.bodySmall
                )
                val who = s.actors.sortedBy { it.kind.ordinal }.mapNotNull { a -> r.assign[a.id]?.let { "${a.title}(${it.label})" } }
                Text("   대상: " + who.joinToString(", ").ifEmpty { "없음" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SavedDialog(list: List<SavedSim>, onOpen: (SavedSim) -> Unit, onDelete: (SavedSim) -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf<SavedSim?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장된 시뮬레이션") },
        text = {
            if (list.isEmpty()) Text("이 기기에 저장된 시뮬레이션이 없습니다.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.size, key = { list[it].id }) { i ->
                    val sim = list[i]
                    AppCard(onClick = { onOpen(sim) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sim.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(sim.company.takeIf { it.isNotBlank() }, "위반 ${sim.settings.rows.size}건", sim.savedAt).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { confirm = sim }) { Text("삭제") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("삭제할까요?") },
            text = { Text("‘${c.name}’을(를) 이 기기에서 삭제합니다. 되돌릴 수 없습니다.") },
            confirmButton = { TextButton(onClick = { onDelete(c); confirm = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun ActorCard(a: Actor, vm: SimulatorViewModel) {
    var open by rememberSaveable(a.id) { mutableStateOf(false) }
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(a.kind.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (a.kind != ActorKind.COMPANY) TextButton(onClick = { vm.removeActor(a.id) }) { Text("삭제") }
            }
            if (a.kind != ActorKind.COMPANY) OutlinedTextField(
                value = a.name, onValueChange = { v -> vm.updateActor(a.id) { it.copy(name = v.take(30)) } }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("이름·직책 (예: 前대표이사, ○○회계법인, 담당이사 김○○)") }
            )
            if (a.kind != ActorKind.CPA) {
                Text(if (a.kind.companySide) "위법행위 내용 중요도 (상: 자금조성·횡령·불공정거래 연계, 가공자산, 증빙 위·변조, 감사방해 / 중: 위법 인식)" else "위법행위 내용 중요도 (상: 감사인 이익 직결·위반 묵인/공모·내부회계 위반 알고도 계약 연장 / 중: 위법 인식)", style = MaterialTheme.typography.bodySmall)
                ChipRow(LEVELS, a.content.label) { l -> vm.updateActor(a.id) { it.copy(content = levelOf(l)) } }
            }
            if (a.kind == ActorKind.OFFICER) NumField("받은 경제적 이익 (억원, 비우면 1억원)", a.benefitEok) { v -> vm.updateActor(a.id) { it.copy(benefitEok = v) } }
            if (a.kind == ActorKind.AUDITOR) NumField("해당 감사로 받은 감사보수 (억원)", a.feeEok) { v -> vm.updateActor(a.id) { it.copy(feeEok = v) } }
            if (a.kind != ActorKind.CPA) {
                Text("통념상 가중 / 합리적 감경·면제 (%)", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("0", "25", "50"), a.upFreePct.toString()) { v -> vm.updateActor(a.id) { it.copy(upFreePct = v.toInt()) } }
                ChipRow(listOf("0", "25", "50", "100"), a.downFreePct.toString()) { v -> vm.updateActor(a.id) { it.copy(downFreePct = v.toInt()) } }
                Text("첫 줄은 가중, 둘째 줄은 감경(100=면제) 비율입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { open = !open }) { Text(if (open) "가중·감경 사유 접기 (${a.reasons.size}개 선택)" else "가중·감경 사유 펼치기 (${a.reasons.size}개 선택)") }
            if (open) {
                val list = if (a.kind.companySide) Reasons.companyUp + Reasons.companyDown else Reasons.auditorUp + Reasons.auditorDown
                ReasonList(list, a.reasons) { id, on -> vm.updateActor(a.id) { it.copy(reasons = it.reasons.toggle(id, on)) } }
            }
        }
    }
}

private fun Set<String>.toggle(id: String, on: Boolean) = if (on) this + id else this - id

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> if (v.all { it.isDigit() || it == '.' || it == ',' } && v.length <= 12) onChange(v) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun ReasonList(reasons: List<Reason>, selected: Set<String>, onToggle: (String, Boolean) -> Unit) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            reasons.forEach { r ->
                val up = r in Reasons.companyUp || r in Reasons.auditorUp
                val auto = r.hint.contains("자동")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = r.id in selected, onCheckedChange = { onToggle(r.id, it) }, enabled = !auto)
                    Column(Modifier.weight(1f)) {
                        Text("${if (up) "▲가중" else "▽감경"} ${r.steps}단계 · ${r.label}", style = MaterialTheme.typography.bodySmall)
                        if (r.hint.isNotEmpty()) Text(r.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaleCard(lines: List<String>) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("중요성 기준금액", style = MaterialTheme.typography.titleSmall)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AssessmentCard(title: String, a: Yangjeong.Assessment, actions: List<String>) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            a.multiples.forEach { (m, x) ->
                val g = a.perMotive[m]
                Text("${m.label}: 규모배수 ${"%.2f".format(x)}배 → " + (g?.let { "중요도 ${Yangjeong.gradeLabel(it)}" } ?: "1배 미만(양정 대상 아님)"), style = MaterialTheme.typography.bodySmall)
            }
            a.converted.forEach { (m, x) -> Text("환산(<표2>) ${m.label} 기준 ${"%.2f".format(x)}배", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            a.base?.let { Text("기본조치 수준: ${it.label}", style = MaterialTheme.typography.bodyMedium) }
            if (a.netSteps != 0) Text("가중·감경 ${if (a.netSteps > 0) "+" else ""}${a.netSteps}단계", style = MaterialTheme.typography.bodyMedium)
            a.final?.let { Text("최종 조치 수준: ${it.label}", style = MaterialTheme.typography.titleSmall, color = AppColors.accent) }
            actions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            a.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FineCard(f: FineBlock) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.title, style = MaterialTheme.typography.titleSmall)
            f.scoreLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val r = f.result
            if (r != null) {
                r.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("예상 과징금 ${Format.won(r.afterCapm)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(f.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
