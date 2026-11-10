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
    "새 메뉴 ‘금융소비자 지킴이’: 보이스피싱 긴급대응(맨 앞 탭), 청약철회·위법계약해지 진단과 요구서 초안, 분쟁조정 사례 검색, 제도권 금융회사 조회, 소비자보호 성적표, 금융꿀팁·소비자경보를 한곳에 모았습니다.",
    "새 메뉴 ‘(베타)제재 양정 시뮬레이터’(금융회사 현황 및 검사): 기관·임원·직원 신분제재 양정과 과징금·과태료를 법령 근거와 함께 모의계산합니다.",
    "새 메뉴 ‘가상자산 시장 동향’: 국내 원화거래소 5곳의 시장 요약, 주요코인 시세, 거래소별 현황, 해외 시장 비교, 시장 일보(PDF·HWPX).",
    "메뉴를 새 순서로 바꿨습니다. ‘금융회사 현황 및 검사’·‘기업공시 및 회계’·‘금융관련 자료’·‘더보기’ 묶음은 제목을 눌러 접고 펼 수 있습니다.",
    "화면 안에 탭이 많아 오른쪽이 가려질 때는 »(오른쪽에 더 있음) 표시가 나타나고, 누르면 다음 탭으로 밀어 줍니다.",
    "회계 시뮬레이터 이름을 ‘(베타) 회계 양정 및 과징금 시뮬레이터’로 정리했습니다."
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
