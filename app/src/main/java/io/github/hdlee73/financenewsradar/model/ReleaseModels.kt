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
    SEC("미국 SEC", "SEC", AgencyGroup.PRESS, "https://www.sec.gov/newsroom/press-releases", "보도자료"),
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

data class UsefulLink(val name: String, val url: String) {
    companion object {
        /** 처음 설치할 때 기본으로 들어가는 사이트. 사용자가 추가·수정·삭제할 수 있다. */
        val DEFAULTS = listOf(
            UsefulLink("금융감독원", "https://www.fss.or.kr"),
            UsefulLink("금융위원회", "https://www.fsc.go.kr"),
            UsefulLink("전자공시시스템 DART", "https://dart.fss.or.kr"),
            UsefulLink("미국 공시시스템 EDGAR", "https://www.sec.gov/edgar/search/"),
            UsefulLink("금융소비자 정보포털 파인", "https://fine.fss.or.kr"),
            UsefulLink("한국거래소", "https://www.krx.or.kr"),
            UsefulLink("국가법령정보센터(법령정보시스템)", "https://www.law.go.kr"),
            UsefulLink("금융투자협회 FreeSIS", "https://freesis.kofia.or.kr"),
            UsefulLink("금융투자협회 채권정보센터", "https://www.kofiabond.or.kr"),
            UsefulLink("금융투자협회 전자공시서비스", "https://dis.kofia.or.kr"),
            UsefulLink("예탁결제원 SEIBro", "https://www.seibro.or.kr"),
            UsefulLink("자본시장연구원", "https://www.kcmi.re.kr"),
            UsefulLink("한국금융연구원", "https://www.kif.re.kr"),
            UsefulLink("IOSCO", "https://www.iosco.org")
        )
    }
}
