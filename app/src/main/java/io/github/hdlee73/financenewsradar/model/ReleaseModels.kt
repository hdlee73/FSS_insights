package io.github.hdlee73.financenewsradar.model

import java.time.LocalDate

enum class AgencyGroup(val label: String) {
    PRESS("금융당국 보도자료"),
    RESEARCH("연구소 최근자료")
}

/** 보도자료·보고서를 가져오는 기관. [homeUrl]은 기관 목록 페이지(사이트에서 직접 보기용). */
enum class AgencyId(val label: String, val group: AgencyGroup, val homeUrl: String, val itemNoun: String) {
    FSS("금융감독원", AgencyGroup.PRESS, "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218", "보도자료"),
    FSC("금융위원회", AgencyGroup.PRESS, "https://www.fsc.go.kr/no010101", "보도자료"),
    KCMI("자본시장연구원", AgencyGroup.RESEARCH, "https://www.kcmi.re.kr/report/report_list", "보고서"),
    KIF("한국금융연구원", AgencyGroup.RESEARCH, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10", "보고서"),
    IOSCO("IOSCO", AgencyGroup.RESEARCH, "https://www.iosco.org/publications/?subsection=public_reports", "보고서")
}

data class ReleaseItem(
    val agency: AgencyId,
    val title: String,
    val link: String,
    val date: LocalDate? = null
)

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
            UsefulLink("국가법령정보센터(법령정보시스템)", "https://www.law.go.kr"),
            UsefulLink("금융투자협회 FreeSIS", "https://freesis.kofia.or.kr"),
            UsefulLink("금융투자협회 채권정보센터", "https://www.bond.kofia.or.kr"),
            UsefulLink("금융투자협회 전자공시서비스", "https://dis.kofia.or.kr"),
            UsefulLink("예탁결제원 SEIBro", "https://www.seibro.or.kr"),
            UsefulLink("자본시장연구원", "https://www.kcmi.re.kr"),
            UsefulLink("한국금융연구원", "https://www.kif.re.kr"),
            UsefulLink("IOSCO", "https://www.iosco.org")
        )
    }
}
