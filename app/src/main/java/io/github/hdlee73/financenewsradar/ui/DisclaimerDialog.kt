package io.github.hdlee73.financenewsradar.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.hdlee73.financenewsradar.data.SettingsStore

const val DISCLAIMER_SHORT = "이 앱은 금융감독원의 공식앱이 아닙니다. 이용에 참고하시기 바랍니다."

const val DISCLAIMER_DETAIL =
    "FSS Insights는 개인이 만든 비공식 앱이며 금융감독원 등 어떤 기관과도 제휴·위탁 관계가 없습니다. " +
        "앱에 표시되는 뉴스, 보도자료, 통계 등은 외부 출처의 공개 자료를 모아 보여주는 것으로, 정확성과 최신성을 보장하지 않습니다. " +
        "중요한 판단에는 반드시 원문과 공식 자료를 확인하시기 바랍니다."

/** 앱을 처음 열 때 한 번만 띄우는 안내. 확인 버튼을 눌러야 닫히며 이후에는 다시 나타나지 않는다. */
@Composable
fun DisclaimerDialog() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    var show by remember { mutableStateOf(!store.disclaimerAccepted()) }
    if (!show) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("안내") },
        text = { Text(DISCLAIMER_SHORT) },
        confirmButton = {
            TextButton(onClick = { store.setDisclaimerAccepted(); show = false }) { Text("확인") }
        }
    )
}
