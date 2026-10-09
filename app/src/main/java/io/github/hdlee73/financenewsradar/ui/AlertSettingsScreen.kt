package io.github.hdlee73.financenewsradar.ui

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.data.BriefingAlert
import io.github.hdlee73.financenewsradar.data.KeywordAlertMode
import io.github.hdlee73.financenewsradar.data.KeywordAlerts
import io.github.hdlee73.financenewsradar.data.NewMaterialAlerts
import io.github.hdlee73.financenewsradar.data.SettingsStore
import io.github.hdlee73.financenewsradar.model.AgencyGroup
import io.github.hdlee73.financenewsradar.model.AgencyId

/** 더보기 > 알림: 오늘의 브리핑 시각, 키워드별 새 기사 알림 주기, 기관별 새 보도자료·연구자료 알림. */
@Composable
fun AlertSettingsScreen(keywords: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    var briefingOn by remember { mutableStateOf(store.briefingAlertEnabled()) }
    var briefingMinutes by remember { mutableIntStateOf(store.briefingAlertMinutes()) }
    val modes = remember(keywords) { mutableStateMapOf<String, KeywordAlertMode>().also { m -> keywords.forEach { m[it] = store.keywordAlertMode(it) } } }
    val materials = remember { mutableStateMapOf<AgencyId, Boolean>().also { m -> NewMaterialAlerts.sources.forEach { m[it] = store.materialAlertEnabled(it) } } }

    // 알림을 처음 켜는 순간 알림 권한이 없으면 먼저 요청하고, 허용되면 하려던 변경을 적용한다.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    fun enabling(action: () -> Unit) {
        if (KeywordAlerts.needsNotificationPermission(context)) {
            pending = action
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
    fun applyBriefing(on: Boolean, minutes: Int) {
        briefingOn = on
        briefingMinutes = minutes
        store.setBriefingAlert(on, minutes)
        BriefingAlert.reschedule(context)
    }
    fun applyMode(keyword: String, mode: KeywordAlertMode) {
        modes[keyword] = mode
        store.setKeywordAlertMode(keyword, mode)
        KeywordAlerts.reschedule(context)
    }
    fun applyMaterial(agency: AgencyId, on: Boolean) {
        materials[agency] = on
        store.setMaterialAlertEnabled(agency, on)
        KeywordAlerts.reschedule(context)
    }
    fun pickTime() {
        TimePickerDialog(context, { _, hour, minute ->
            val minutes = hour * 60 + minute
            if (briefingOn) applyBriefing(true, minutes) else enabling { applyBriefing(true, minutes) }
        }, briefingMinutes / 60, briefingMinutes % 60, true).show()
    }

    Column(modifier.fillMaxSize()) {
        LargeTitle("알림")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 1) 오늘의 브리핑
            Section("오늘의 브리핑") {
                SwitchRow(
                    "매일 브리핑 알림",
                    if (briefingOn) "매일 ${BriefingAlert.formatTime(briefingMinutes)} 무렵 코스피·코스닥, 내 키워드 주요 기사, 새 보도자료를 요약해 알려 드립니다."
                    else "켜면 정한 시각에 오늘의 브리핑을 요약해 알려 드립니다.",
                    briefingOn
                ) { on -> if (on) enabling { applyBriefing(true, briefingMinutes) } else applyBriefing(false, briefingMinutes) }
                Row(
                    Modifier.fillMaxWidth().clickable { pickTime() }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("알림 시각", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(BriefingAlert.formatTime(briefingMinutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Note("정확한 알람 권한을 쓰지 않아 정한 시각보다 몇 분 늦을 수 있습니다. 절전 모드가 길게 이어지면 더 늦어질 수 있습니다.")
            }
            SectionBand(6.dp)

            // 2) 키워드별 새 기사 알림
            Section("키워드 새 기사 알림") {
                Note("키워드마다 확인 주기를 고릅니다. ‘약 15분’은 안드로이드가 앱에 허용하는 가장 짧은 주기이고, 절전 모드에서는 더 늦어질 수 있습니다. 서버에서 보내는 즉시 푸시(진짜 실시간)는 아직 지원하지 않습니다. 같은 기사는 한 번만 알리며, 켜기 전에 이미 나온 기사는 알리지 않습니다.")
                if (keywords.isEmpty()) Note("저장된 키워드가 없습니다. 뉴스 검색 탭의 설정에서 키워드를 추가하세요.")
                keywords.forEach { keyword ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(keyword, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeywordAlertMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = modes[keyword] == mode,
                                    onClick = {
                                        if (mode == KeywordAlertMode.OFF) applyMode(keyword, mode) else enabling { applyMode(keyword, mode) }
                                    },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }
                }
            }
            SectionBand(6.dp)

            // 3) 새 보도자료·연구자료 알림
            for (group in AgencyGroup.entries) {
                Section(if (group == AgencyGroup.PRESS) "새 보도자료 알림" else "새 연구보고서 알림") {
                    if (group == AgencyGroup.PRESS) Note("자료가 새로 올라오면 1시간 안팎 간격으로 확인해 알려 드립니다. 켜기 전에 이미 올라온 자료는 알리지 않습니다.")
                    NewMaterialAlerts.sources.filter { it.group == group }.forEach { agency ->
                        SwitchRow(
                            if (agency == AgencyId.CUSTOM) "직접 추가한 연구소" else agency.label,
                            null,
                            materials[agency] == true
                        ) { on -> if (on) enabling { applyMaterial(agency, true) } else applyMaterial(agency, false) }
                    }
                }
                SectionBand(6.dp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
