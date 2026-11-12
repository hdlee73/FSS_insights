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
import androidx.compose.material3.Switch
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
import io.github.hdlee73.financenewsradar.data.UsageStats
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.data.UpdateChecker
import io.github.hdlee73.financenewsradar.data.UpdateStatus

private const val USAGE_GUIDE =
    "• 왼쪽 위 ☰ 버튼(또는 화면 왼쪽 가장자리 밀기)으로 메뉴를 열고 닫습니다. ‘금융회사 현황 및 검사’·‘기업공시 및 회계’·‘금융관련 자료’·‘더보기’ 묶음 제목을 누르면 하위 메뉴를 접고 펼 수 있고, 접어 둔 상태는 기억됩니다. 뒤로가기는 앱을 끄지 않고 직전 화면으로 돌아갑니다.\n" +
        "• 오늘의 브리핑: 관심 키워드 뉴스·보도자료·시장지표를 한눈에 봅니다.\n" +
        "• 뉴스검색: 키워드·회사명으로 검색하고 AND, OR, 따옴표, -제외어를 쓸 수 있습니다.\n" +
        "• 금융시장 동향: 지수·환율·원자재와 투자자 매매동향, 전일 비교 그래프를 봅니다.\n" +
        "• 스마트 법령검색: 법령 비교표와 조문을 찾고 PDF·한글(HWPX)로 저장합니다.\n" +
        "• 금융소비자 지킴이: 가입·로그인 없이 쓰는 금융소비자 권리 도구입니다. 맨 앞 ‘보이스피싱 긴급대응’은 상황(송금·앱 설치·정보 제공·의심)별로 지급정지·피해구제신청·환급 절차를 순서대로 안내하고 112·1332로 바로 전화할 수 있습니다. 그 밖에 상황별 시작, 청약철회 D-Day·위법계약해지 점검과 요구서 초안(권리구제 진단기), 분쟁조정 사례 검색, 제도권 금융회사 조회, 소비자보호 성적표, 금융꿀팁·소비자경보 탭이 있고, 탭이 화면보다 많으면 오른쪽 »를 눌러 더 봅니다. 입력한 내용은 기기 밖으로 나가지 않으며 법률자문이 아닌 참고용입니다.\n" +
        "• [금융회사 현황 및 검사] 금융회사 주요 현황: 권역별 금융회사의 경영지표를 보고 2~3곳을 비교합니다. 회사를 고르면 ‘최근 제재내역’ 탭에서 그 회사의 제재공시를 봅니다. / 금융회사 제재 공시: 2011년 이후 금감원 제재공시 전체 목록입니다. 공시마다 기관·임원·직원 조치와 지적제목(위반 법률·조항)을 보여 주고, 회사명·지적제목·법조문으로 검색하고 기간·권역으로 거르며 ‘통계’ 탭과 위 ⬇ 버튼(엑셀 저장)을 씁니다. 위반 조항을 누르면 스마트 법령검색으로 이동합니다. / (베타)제재 양정 시뮬레이터: 검사건 안의 여러 제재건에 대해 기관·임원·직원 신분제재 양정(제재규정·시행세칙)과 과징금·과태료(금융 17개 법률)를 따로 모의계산하고, 모든 계산 줄에서 적용 근거와 국가법령정보센터 링크를 봅니다. 산정 내역은 엑셀로 저장하며 실제 제재와 다를 수 있는 참고용입니다.\n" +
        "• [기업공시 및 회계] 기업 개황 및 공시(DART): 회사명·종목코드로 검색해 개황·최대주주·재무손익·정기/주요사항/기타 공시와 주가를 봅니다. 공시는 1년·3년·5년·기간선택(최대 10년)으로 조회하고, 별(★)로 관심기업에 담으면 첫 화면에서 새 공시를 모아 봅니다.\n" +
        "   - 공시조사결과제재: 금감원 ‘공시위반 조치 내역’(증권선물위원회·금융위원회) 가운데 2020년 이후 글을 첨부파일 세부내역까지 읽어 회사별로 나누어 보여 줍니다. 연도·조치(과징금, 증권발행제한, 경고·주의, 고발·통보)로 거르고, 위반 조문을 누르면 스마트 법령검색으로 이동합니다.\n" +
        "   - 회계감리 조치현황: 금감원 회계감리 결과 조치(지적사항, 과징금·감사인지정·고발 등)를 시장·조치별로 거르고 상세를 봅니다. 과징금은 금융위원회 결정 후 확정되므로 ‘결정 예정’으로 표시될 수 있습니다.\n" +
        "   - 두 제재 화면에서 회사명(파란 밑줄)을 누르면 ‘기업 개황 및 공시(DART)’에서 그 회사를 바로 찾고, 뒤로가기로 돌아옵니다.\n" +
        "   - 회계기준서·질의회신·지적사례: ‘통합’ 탭은 기준서·질의회신·해석·감리지적사례를 한 번에 찾아 종류별로 보여 주고 개별 탭에서 한 종류만 자세히 봅니다. 항목을 누르면 원문이 열리며 출처는 ‘원본 기관 / 수집 경로’로 표기됩니다.\n" +
        "   - (베타) 회계 양정 및 과징금 시뮬레이터: 회사(상장사는 주가·거래대금 자동 조회)와 대상자(임직원·감사인·공인회계사 포함)별 위반행위·가중감경 사유를 넣으면 시행세칙 양정기준과 외감규정 별표 8로 조치수준과 과징금을 모의계산합니다(참고용). 계산 내역은 엑셀로 저장하고, 시뮬레이션은 이 기기에만 저장·불러오기 합니다.\n" +
        "• 가상자산 시장 동향: 국내 원화거래소 5곳(업비트·빗썸·코인원·코빗·고팍스)을 매일 12:00·24:00에 집계한 시장 요약, 업비트 주요코인 시세(당일·1년), 거래소별 현황, 해외 시장 비교(글로벌 시총·BTC 도미넌스·김치 프리미엄), 시장 일보(PDF·HWPX)를 봅니다. 정보 제공용이며 투자 권유가 아닙니다.\n" +
        "• [금융관련 자료] 금융당국 등 보도자료·유권해석·비조치의견서·행정지도·금융관련 리포트(금융감독연구, 자본시장연구원, 한국금융연구원 등)·기타 참고자료: 새 자료를 모아 보고 검색합니다.\n" +
        "• [더보기] 주요사이트·알림: 키워드·자료별 알림을 켜고 끕니다. 앱정보: 버전·업데이트 확인과 익명 사용 통계 전송을 켜고 끌 수 있고, 새 버전은 여기서 바로 업데이트합니다."

/** 앱 이름·버전·만든이와 업데이트 확인(릴리스 노트 표시, 새 버전 자동 내려받기·설치). */
@Composable
fun AppInfoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by UpdateStatus.available.collectAsStateWithLifecycle()
    val state by UpdateStatus.state.collectAsStateWithLifecycle()
    val flow = rememberUpdateInstallFlow()
    val busy = flow.busy

    Column(modifier.fillMaxSize()) {
    LargeTitle("앱 정보")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoRow("앱 이름", "FSS Insights")
        InfoRow("버전", BuildConfig.VERSION_NAME)
        InfoRow("만든이", "이현덕 with Claude")
        InfoRow("이메일", "hdlee73@gmail.com")
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("홈페이지", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { runCatching { uriHandler.openUri("https://hdlee73.github.io/Home/") } }) { Text("hdlee73.github.io/Home") }
        }

        Text("사용 방법", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(USAGE_GUIDE, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        Text("회계 자료 출처", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(
            "• 공시조사결과제재·회계감리결과제재·회계 게시판: 금융감독원 / 직접 수집\n" +
                "• 기준서(K-IFRS·일반기업회계기준)·질의회신·해석·감리지적사례: 한국회계기준원·금융감독원·한국공인회계사회 등 원본 기관 / 회계위키 경유\n" +
                "• 질의회신 요약: 한국회계기준원 / 직접 수집, 기타 기준서: 한국회계기준원 누리집 연결\n" +
                "• 양정기준: 외부감사 및 회계 등에 관한 규정 별표 8·시행세칙 별표 1 / 국가법령정보센터\n" +
                "• 주가·거래대금(양정 시뮬레이터): 네이버 증권 공개 시세(근사치)\n" +
                "각 자료의 저작권은 원본 기관에 있으며, 회계위키 경유 자료는 공개 목록·링크를 정리한 색인입니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Text("면책 안내", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(DISCLAIMER_SHORT, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(DISCLAIMER_DETAIL, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        Text("사용 통계", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        var usageOn by remember { mutableStateOf(UsageStats.isEnabled(context)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("익명 사용 통계 보내기", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = usageOn, onCheckedChange = { usageOn = it; UsageStats.setEnabled(context, it) })
        }
        Text(
            "앱 개선을 위해 하루 한 번, 무작위로 만든 설치 번호·앱 버전·날짜·메뉴별 이용 횟수만 서버로 보냅니다. 이름, 계정, 기기 정보, 검색어, 열람한 내용은 보내지 않으며, 꺼도 앱 기능은 그대로 쓸 수 있습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

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
            if (UpdateChecker.REQUIRES_REINSTALL) {
                ReinstallGuide(Modifier.padding(top = 8.dp))
            } else if (info.apkUrl != null) {
                val sizeText = if (info.apkSize > 0) " (${"%.1f".format(info.apkSize / 1_048_576.0)}MB)" else ""
                Button(
                    onClick = { flow.start(info) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("새 버전 내려받기$sizeText") }
                flow.progress?.let { p ->
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "브라우저가 APK를 내려받습니다. 다 받으면 알림의 APK를 눌러 설치하세요. 처음에는 브라우저에 '출처를 알 수 없는 앱 설치' 허용이 필요할 수 있습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        flow.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        flow.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = {
                val link = update?.url ?: UpdateChecker.RELEASES_URL
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("GitHub 릴리스 페이지 열기") }
        OutlinedButton(
            onClick = {
                val log = io.github.hdlee73.financenewsradar.CrashLogger.latest(context)
                if (log == null) android.widget.Toast.makeText(context, "저장된 오류 기록이 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                else runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "오류 기록 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("최근 오류 기록 공유") }
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
