package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.data.UpdateStatus

/**
 * 앱을 열 때 새 버전이 있으면 띄우는 업데이트 팝업. '나중에'로 닫아도 설치하기 전에는
 * 앱을 다시 시작할 때마다 뜬다(이번 실행 중에만 닫힌 상태를 기억).
 */
@Composable
fun UpdatePrompt(onOpenAppInfo: () -> Unit) {
    val info by UpdateStatus.available.collectAsStateWithLifecycle()
    var dismissedVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val flow = rememberUpdateInstallFlow()
    val update = info ?: return
    if (dismissedVersion == update.version && !flow.busy) return

    AlertDialog(
        onDismissRequest = { if (!flow.busy) dismissedVersion = update.version },
        title = { Text("새 버전 ${update.version} 업데이트") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("현재 ${BuildConfig.VERSION_NAME} → 최신 ${update.version}", style = MaterialTheme.typography.bodyMedium)
                if (update.notes.isNotBlank()) {
                    Text(update.notes, Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                flow.progress?.let { p ->
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                flow.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (update.apkUrl != null) {
                TextButton(onClick = { flow.start(update) }, enabled = !flow.busy) { Text("지금 업데이트") }
            } else {
                TextButton(onClick = { dismissedVersion = update.version; onOpenAppInfo() }) { Text("앱 정보로 이동") }
            }
        },
        dismissButton = { TextButton(onClick = { dismissedVersion = update.version }, enabled = !flow.busy) { Text("나중에") } }
    )
}

