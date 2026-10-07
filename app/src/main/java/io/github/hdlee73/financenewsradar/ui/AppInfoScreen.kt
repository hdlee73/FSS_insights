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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.data.UpdateChecker
import io.github.hdlee73.financenewsradar.data.UpdateStatus

/** 앱 이름·버전·만든이·업데이트 정보(깃허브 릴리스 페이지 연결). */
@Composable
fun AppInfoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val update by UpdateStatus.available.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
    LargeTitle("앱 정보")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoRow("앱 이름", "FSS Insights")
        InfoRow("버전", BuildConfig.VERSION_NAME)
        InfoRow("만든이", "이현덕 (hdlee73@gmail.com)")
        val link = update?.url ?: UpdateChecker.RELEASES_URL
        update?.let {
            Text(
                "새 버전 ${it.version}이(가) 나왔습니다. 아래 버튼에서 받을 수 있습니다.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedButton(
            onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (update != null) "새 버전 받기 (GitHub 릴리스)" else "업데이트 정보 (GitHub 릴리스)") }
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
