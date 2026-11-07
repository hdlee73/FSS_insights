package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.data.SettingsStore

/** 이번 버전에서 바뀐 내용만 담는다. 버전을 올릴 때마다 이 목록을 새로 쓴다. */
private val WHATS_NEW = listOf(
    "제재현황 카드에 [조치내용]과 [지적사항] 제목을 붙여 두 부분을 구분해 보여 줍니다. 지적사항의 위반 조항을 누르면 스마트 법령검색이 열리는 것은 그대로입니다.",
    "권역을 FISIS 금융권역에 맞춰 다시 나눴습니다. 부동산신탁사(부신사)와 금융지주가 새 권역이고, 유사투자자문업자는 증권이 아니라 기타로 분류합니다.",
    "금융회사 주요 현황에 부신사(부동산신탁사) 권역이 추가됩니다.",
    "연금감독실이 올린 공시 등 일부 공시의 조치내용이 보이지 않던 오류를 고쳤습니다."
)

/** 업데이트를 설치하고 처음 열 때 한 번만 띄운다. 처음 설치한 경우에는 띄우지 않는다. */
@Composable
fun WhatsNewDialog() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    var show by remember {
        val current = BuildConfig.VERSION_NAME
        val last = store.lastSeenVersion()
        val upgraded = last != current && store.disclaimerAccepted()
        if (last != current) store.setLastSeenVersion(current)
        mutableStateOf(upgraded)
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("v${BuildConfig.VERSION_NAME} 업데이트 내용") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                WHATS_NEW.forEach { Text("• $it", modifier = Modifier.padding(bottom = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text("확인") } }
    )
}
