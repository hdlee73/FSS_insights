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
    "금융회사 제재공시에 ‘세부내역·통계’ 탭이 생겼습니다. 2016년 이후 제재를 지적사항 단위로 권역·회사·조치요구일·지적제목·조치수준·위반 법률·조항과 함께 봅니다.",
    "기간(올해·최근 1/3/5년·직접 지정)·권역·조치수준·검색어로 거르고, 연도·월별 추이와 권역·조치 종류·자주 지적된 법조문 통계를 봅니다.",
    "위반 조항을 누르면 스마트 법령검색이 그 조문으로 바로 열립니다. 세부내역과 통계는 엑셀(.xlsx)로 저장할 수 있습니다.",
    "‘최근 공시’ 첫 화면은 그대로이고 50건까지 보여 줍니다."
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
