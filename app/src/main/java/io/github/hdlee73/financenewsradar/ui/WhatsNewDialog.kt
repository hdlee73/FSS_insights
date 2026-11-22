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
    "메뉴 구성이 새로 바뀌었습니다(v3.0). 화면 아래 탭에서 뉴스검색·금융시장·소비자·법령을 언제든 한 번에 열 수 있고, ‘전체’ 탭에서 나머지 모든 메뉴(즐겨찾기 포함)를 엽니다.",
    "위쪽 ‘FSS Insights’ 제목이나 집 모양 버튼을 누르면 오늘의 브리핑(홈)으로 돌아갑니다. 모든 기능과 즐겨찾기·뒤로가기 동작은 그대로입니다.",
    "오늘의 브리핑 첫 화면도 새로 정리했습니다. 시장 지표는 옆으로 밀어 보는 한 줄 카드, 그 아래 최신 보도자료와 최근 제재공시가 먼저 나옵니다.",
    "새 메뉴 ‘최근 금융관련 법령개정 소식’: 주요 금융 법령 20개의 최근 제·개정(공포일·시행일·개정 구분)을 한눈에 보고, 법령명을 누르면 스마트 법령검색으로 이동합니다.",
    "새 메뉴 ‘익명게시판’과 ‘(베타) 커뮤니티 핫이슈(금융관련)’(더보기)가 생겼습니다. 하단 탭에는 가상자산도 추가했습니다.",
    "금융시장: 나스닥100 선물을 기본 지표에 넣고, 1년 그래프에 시작·최고·최저를 표시하며, 아시아 지수 장중·점심 휴장 표시와 수능일 등 지연개장일 휴장 오판을 고쳤습니다.",
    "가상자산: 코인 4종 추가와 코인·티커 검색, 시세 첫 표시 단축. 알림 설정에 제재 알림이 추가되었고, 금융소비자 지킴이에 신용회복위원회 링크가 생겼습니다.",
    "화면이 숨겨지거나 오프라인일 때 자동 갱신을 멈추고 실패 시 천천히 재시도해 배터리·데이터를 아낍니다. 웹 버전(v3.10)과 같은 구성입니다."
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
