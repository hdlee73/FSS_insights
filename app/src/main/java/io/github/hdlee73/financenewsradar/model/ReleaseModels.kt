package io.github.hdlee73.financenewsradar.model

import java.time.LocalDate

enum class AgencyGroup(val label: String) {
    PRESS("금융당국 보도자료"),
    RESEARCH("금융관련 연구원 자료")
}

/** 보도자료·보고서를 가져오는 기관. [homeUrl]은 기관 목록 페이지(사이트에서 직접 보기용). */
enum class AgencyId(val label: String, val shortLabel: String, val group: AgencyGroup, val homeUrl: String, val itemNoun: String) {
    FSS("금융감독원", "금융감독원", AgencyGroup.PRESS, "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218", "보도자료"),
    FSC("금융위원회", "금융위원회", AgencyGroup.PRESS, "https://www.fsc.go.kr/no010101", "보도자료"),
    KCMI("자본시장연구원", "자본시장연구원", AgencyGroup.RESEARCH, "https://www.kcmi.re.kr/report/report_list", "보고서"),
    KIF("한국금융연구원", "금융연구원", AgencyGroup.RESEARCH, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10", "보고서"),
    IOSCO("IOSCO", "IOSCO", AgencyGroup.RESEARCH, "https://www.iosco.org/publications/?subsection=public_reports", "보고서"),
    /** 사용자가 직접 추가한 연구소(이름·주소는 [CustomInstitute]). */
    CUSTOM("추가한 연구소", "추가", AgencyGroup.RESEARCH, "", "자료");

    /** 처음 보여 줄 최근 자료 수: 보도자료·연구자료 모두 10건. */
    val latestCount: Int get() = 10
}

data class ReleaseItem(
    val agency: AgencyId,
    val title: String,
    val link: String,
    val date: LocalDate? = null,
    /** 사용자가 추가한 연구소의 이름(내장 기관이면 null). */
    val sourceLabel: String? = null
) {
    val label: String get() = sourceLabel ?: agency.shortLabel
}

data class CustomInstitute(val name: String, val url: String)

data class ReleasePage(
    val items: List<ReleaseItem>,
    val hasMore: Boolean = false,
    val nextPage: Int = 1
)

data class UsefulLink(val name: String, val url: String, val note: String = "") {
    companion object {
        /** 내장 사이트의 기본 설명(주소에 포함된 도메인 기준). 사용자가 직접 쓴 설명이 있으면 그것이 우선한다. */
        private val NOTES = listOf(
            "fss.or.kr" to "보도자료·검사·제재 공시, 감독규정, 금융소비자 보호 정보 등 금융감독원의 공식 발표와 안내",
            "fsc.go.kr" to "금융정책·법령 개정, 보도자료, 규제 샌드박스, 입법예고 등 금융위원회의 정책 발표",
            "dart.fss.or.kr" to "상장·등록법인의 사업보고서, 분기보고서, 주요사항보고 등 공시 서류 조회",
            "fine.fss.or.kr" to "금융상품 비교·공시, 금융소비자 경보, 민원·분쟁 사례 등 소비자 정보",
            "krx.or.kr" to "상장 종목·시세·시장 통계, 시장 공시와 거래소 규정",
            "law.go.kr" to "법령·행정규칙·자치법규·판례 조회와 최신 개정 이력",
            "freesis.kofia.or.kr" to "펀드·채권·주식 등 금융투자 시장 통계와 업권별 현황",
            "kofiabond.or.kr" to "채권 시가평가 기준수익률, 발행·거래 정보와 채권 시장 통계",
            "dis.kofia.or.kr" to "금융투자회사 현황, 펀드 공시, 협회 전자공시",
            "seibro.or.kr" to "주식·채권·펀드의 예탁·결제 통계와 권리·배당 일정 등 증권정보",
            "kcmi.re.kr" to "자본시장 이슈·정책 연구보고서, 세미나 자료, 시장 통계",
            "kif.re.kr" to "금융·은행·보험 분야 연구보고서와 정책 이슈 분석",
            "iosco.org" to "증권감독자국제기구(IOSCO)의 국제 규제 기준, 보고서, 공개 문서"
        )

        fun defaultNote(url: String): String {
            val host = url.substringAfter("://").substringBefore('/').lowercase()
            return NOTES.filter { host == it.first || host.endsWith("." + it.first) }
                .maxByOrNull { it.first.length }?.second.orEmpty()
        }

        private fun d(name: String, url: String) = UsefulLink(name, url, defaultNote(url))

        /** 처음 설치할 때 기본으로 들어가는 사이트. 사용자가 추가·수정·삭제할 수 있다. */
        val DEFAULTS = listOf(
            d("금융감독원", "https://www.fss.or.kr"),
            d("금융위원회", "https://www.fsc.go.kr"),
            d("전자공시시스템 DART", "https://dart.fss.or.kr"),
            d("금융소비자 정보포털 파인", "https://fine.fss.or.kr"),
            d("한국거래소", "https://www.krx.or.kr"),
            d("국가법령정보센터(법령정보시스템)", "https://www.law.go.kr"),
            d("금융투자협회 FreeSIS", "https://freesis.kofia.or.kr"),
            d("금융투자협회 채권정보센터", "https://www.kofiabond.or.kr"),
            d("금융투자협회 전자공시서비스", "https://dis.kofia.or.kr"),
            d("예탁결제원 SEIBro", "https://www.seibro.or.kr"),
            d("자본시장연구원", "https://www.kcmi.re.kr"),
            d("한국금융연구원", "https://www.kif.re.kr"),
            d("IOSCO", "https://www.iosco.org")
        )
    }
}
