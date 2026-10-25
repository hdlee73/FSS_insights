package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 서명키 교체로 새 버전을 기존 앱 위에 설치할 수 없을 때 보여 주는 삭제 후 재설치 안내. */
internal const val REINSTALL_INTRO =
    "앱 보안을 위해 서명키를 교체했습니다. 서명이 달라 기존 앱 위에 바로 업데이트할 수 없으니, 아래 순서대로 앱을 삭제한 뒤 새로 설치해 주세요."

internal val REINSTALL_STEPS = listOf(
    "삭제하면 이 기기의 설정(관심종목, 알림, 키워드 등)이 사라집니다. 필요하면 지금 설정 화면을 캡처하거나 적어 두세요.",
    "'다운로드 페이지 열기'를 눌러 새 버전 APK 파일(fss-insights-v….apk)을 내려받아 둡니다.",
    "휴대폰 설정 > 앱 > FSS Insights > 삭제를 누릅니다. (홈 화면 앱 아이콘을 길게 눌러 '앱 삭제'를 해도 됩니다.)",
    "내려받은 APK 파일(알림창 또는 '내 파일 > 다운로드')을 눌러 설치합니다. '출처를 알 수 없는 앱' 허용 창이 나오면 해당 앱(브라우저·내 파일)에 허용해 주세요.",
    "새 앱을 열어 알림을 허용하고, 메모해 둔 관심종목·알림 설정을 다시 맞춥니다."
)

internal const val REINSTALL_FOOTER = "한 번만 하면 됩니다. 이후 업데이트는 지금처럼 앱에서 자동으로 설치됩니다."

@Composable
internal fun ReinstallGuide(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        Text(REINSTALL_INTRO, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        REINSTALL_STEPS.forEachIndexed { i, s ->
            Text("${i + 1}. $s", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text(REINSTALL_FOOTER, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
