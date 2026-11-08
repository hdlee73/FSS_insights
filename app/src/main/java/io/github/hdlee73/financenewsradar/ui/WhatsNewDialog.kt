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
    "회계 파트를 합쳤습니다: 회계감리 조치현황, 회계기준서·질의회신·지적사례 검색, (회계)과징금 및 양정 시뮬레이터가 ‘기업공시 및 회계’ 메뉴에 들어왔습니다(회계 법령검색은 스마트 법령검색을 그대로 씁니다).",
    "새 메뉴 ‘공시조사결과제재’: 금감원 공시위반 조치 내역 가운데 2020년 이후 글을 첨부 세부내역까지 읽어 회사별로 보여 줍니다.",
    "제재 화면의 회사명을 누르면 ‘기업 개황 및 공시(DART)’에서 그 회사를 바로 찾습니다.",
    "금융관련 리포트에 ‘금융감독연구’(금감원 연구논문)를 자본시장연구원 앞에 추가했습니다.",
    "메뉴를 금융회사·기업공시 및 회계·자료·더보기 묶음으로 바꾸고, 묶음 제목을 눌러 하위 메뉴를 접고 펼 수 있게 했습니다.",
    "안드로이드와 웹 버전 번호를 2.5.0으로 맞췄습니다."
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
