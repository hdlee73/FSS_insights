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
    "제재현황에서 부신사·금융지주 권역을 골라도 내역이 보이지 않던 문제를 고쳤습니다(예전에 받아 둔 제재 자료를 새로 받도록 했습니다).",
    "조치내용이 비어 보이던 공시(농협은행·JB금융지주 등)를 다시 읽어 채웠고, 재심·재조치 공시와 표 양식이 다른 공시도 읽도록 보강했습니다."
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
