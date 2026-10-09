package io.github.hdlee73.financenewsradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hdlee73.financenewsradar.data.CheckState
import io.github.hdlee73.financenewsradar.data.UpdateInfo
import io.github.hdlee73.financenewsradar.data.UpdateInstaller
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.data.UpdateChecker
import io.github.hdlee73.financenewsradar.data.UpdateStatus

/** 앱 이름·버전·만든이와 업데이트 확인(릴리스 노트 표시, 새 버전 자동 내려받기·설치). */
@Composable
fun AppInfoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by UpdateStatus.available.collectAsStateWithLifecycle()
    val state by UpdateStatus.state.collectAsStateWithLifecycle()
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    val busy = progress != null

    fun startDownload(info: UpdateInfo) {
        scope.launch {
            message = null
            progress = 0f
            try {
                val apk = UpdateInstaller.download(context, info) { progress = it }
                downloaded = apk
                progress = null
                UpdateInstaller.install(context, apk)
            } catch (e: Exception) {
                progress = null
                message = e.message ?: "내려받기에 실패했습니다."
            }
        }
    }

    // '출처를 알 수 없는 앱 설치'를 허용하고 돌아오면 받아 둔 APK 설치를 이어 간다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && downloaded != null && UpdateInstaller.canInstall(context)) {
                val apk = downloaded
                downloaded = null
                if (apk != null && apk.exists()) runCatching { UpdateInstaller.install(context, apk) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize()) {
    LargeTitle("앱 정보")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoRow("앱 이름", "FSS Insights")
        InfoRow("버전", BuildConfig.VERSION_NAME)
        InfoRow("만든이", "이현덕 (hdlee73@gmail.com)")

        Text("업데이트", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        InfoRow("현재", BuildConfig.VERSION_NAME)
        InfoRow("최신", update?.version ?: if (state == CheckState.LATEST) BuildConfig.VERSION_NAME else "-")
        Text(
            when (state) {
                CheckState.CHECKING -> "최신 버전을 확인하는 중입니다."
                CheckState.LATEST -> "최신 버전을 사용하고 있습니다."
                CheckState.FAILED -> "확인하지 못했습니다. 인터넷 연결을 확인해 주세요."
                CheckState.AVAILABLE -> "새 버전 ${update?.version}이(가) 나왔습니다."
                CheckState.IDLE -> "아래 버튼으로 새 버전이 있는지 확인합니다."
            },
            color = if (state == CheckState.AVAILABLE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (state == CheckState.AVAILABLE) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = { scope.launch { UpdateChecker.check(context.applicationContext) } },
            enabled = state != CheckState.CHECKING && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("업데이트 확인") }

        val info = update
        if (info != null) {
            if (info.notes.isNotBlank()) {
                Text("업데이트 내용", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(info.notes, style = MaterialTheme.typography.bodyMedium)
            }
            if (info.apkUrl != null) {
                val sizeText = if (info.apkSize > 0) " (${"%.1f".format(info.apkSize / 1_048_576.0)}MB)" else ""
                Button(
                    onClick = {
                        if (UpdateInstaller.canInstall(context)) startDownload(info)
                        else {
                            message = "이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 돌아오면 이어서 설치합니다."
                            scope.launch {
                                // 허용 화면으로 가기 전에 APK부터 받아 둔다.
                                try {
                                    progress = 0f
                                    downloaded = UpdateInstaller.download(context, info) { progress = it }
                                    progress = null
                                    UpdateInstaller.openInstallPermissionSettings(context)
                                } catch (e: Exception) {
                                    progress = null
                                    message = e.message ?: "내려받기에 실패했습니다."
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("자동 업데이트: 내려받아 설치$sizeText") }
                progress?.let { p ->
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "안드로이드 보안 정책상 설치 확인 창이 한 번 나타나며, 처음에는 '출처를 알 수 없는 앱 설치' 허용이 필요합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = {
                val link = update?.url ?: UpdateChecker.RELEASES_URL
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("GitHub 릴리스 페이지 열기") }
    }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
