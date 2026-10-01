package io.github.hdlee73.financenewsradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.data.PublisherCatalog

@Composable
internal fun SearchHelpDialog(topic: String, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (topic == "outlets") "30대 언론 선정 기준" else "검색 방식·네이버 연결") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (topic == "outlets") {
                    Text("공식 순위나 발행부수 상위 30곳을 뜻하지 않습니다. 국내 금융 뉴스를 확인하기 위해 통신·방송 11곳, 종합지 10곳, 경제·산업지 9곳을 고르게 포함한 앱의 고정 목록입니다.")
                    HelpSection("통신·방송 (11)", PublisherCatalog.major30.take(11).joinToString(" · "))
                    HelpSection("종합지 (10)", PublisherCatalog.major30.drop(11).take(10).joinToString(" · "))
                    HelpSection("경제·산업지 (9)", PublisherCatalog.major30.drop(21).joinToString(" · "))
                    Text("기사의 출처명과 링크 도메인을 이 목록에 대조합니다. 표기 차이나 도메인 식별 때문에 제외될 수 있습니다. 지역지·전문지 등까지 보고 싶으면 ‘전체 언론’을 선택하세요. 검색 서비스에 수집된 결과 안에서 적용되는 필터이며, 모든 기사를 직접 수집하는 기능은 아닙니다.")
                } else {
                    HelpSection("바로 검색", "키 입력 없이 Google 뉴스 RSS만 사용합니다. 빠르게 시작할 수 있지만 서비스가 반환하는 기사 범위와 건수에 제한이 있고, 이전 기사 추가 페이지는 제공되지 않습니다.")
                    HelpSection("통합 검색", "Google과 네이버 결과를 함께 모아 중복을 정리합니다. 네이버 키가 없으면 Google만 사용합니다. 키가 있으면 네이버 결과를 추가하고, 직접 검색 결과의 ‘이전 기사 더 불러오기’로 네이버 페이지를 이어볼 수 있습니다.")
                    HelpSection("네이버 심층 검색", "네이버 뉴스 검색 API만 사용합니다. 키가 필요하며 직접 검색 시 100건씩 추가해 검색식별 최대 1,000번째 결과까지 이어봅니다. ‘심층’은 더 많은 검색결과를 불러온다는 의미이며 기사 본문 분석 기능은 아닙니다. 기간·언론 필터와 중복 제거 후 표시 건수는 줄어들 수 있습니다. 맞춤 뉴스 홈은 첫 페이지를 모읍니다.")
                    HelpSection("신규 키 발급 (NAVER API HUB)", "1. 네이버 클라우드 플랫폼에 가입·로그인하고 콘솔을 엽니다.\n2. All Services → Application Services → NAVER API HUB에서 서비스 이용을 신청합니다.\n3. Application → 등록에서 ‘뉴스’ API를 선택하고 앱 이름을 등록합니다.\n4. 등록한 Application의 ‘인증 정보’에서 Client ID와 Client Secret을 복사합니다.\n5. 이 앱 설정에서 ‘NAVER API HUB (신규 키)’를 선택하고 두 값을 붙여넣은 뒤 저장합니다.\n6. 필터에서 네이버 심층 검색 또는 통합 검색을 선택합니다.")
                    Text("키 발급 화면의 요금 안내·사용 한도를 확인하세요. 키는 해당 휴대폰에서 암호화해 저장합니다. 다른 사람에게 공개하거나 공유하지 마세요.")
                    TextButton(onClick = { open("https://guide.ncloud-docs.com/docs/apihub-application") }) { Text("공식 발급 안내") }
                    TextButton(onClick = { open("https://console.ncloud.com/") }) { Text("네이버 클라우드 콘솔") }
                    HelpSection("기존 개발자센터 키", "2026년 7월 31일 이전에 검색 API를 신청한 키는 ‘개발자센터 (기존 키)’를 선택합니다. 현재 공식 유예기간은 2027년 6월 30일까지입니다. 업데이트 시 기존 키의 연결 방식은 유지합니다. 새 API HUB 키와 기존 키는 서로 바꿔 쓸 수 없습니다.")
                    TextButton(onClick = { open("https://developers.naver.com/products/terms/") }) { Text("기존 키 지원 안내") }
                    HelpSection("연결이 안 될 때", "발급처 선택과 키 두 값을 확인하고 해당 Application에 뉴스 API가 등록됐는지 확인하세요. 호출 한도 초과나 서비스 오류도 원인이 될 수 있습니다. 연결 전에는 바로 검색을 사용할 수 있습니다.")
                    TextButton(onClick = onOpenSettings) { Text("앱 설정 열기") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(title, fontWeight = FontWeight.Bold)
    Text(body)
}
