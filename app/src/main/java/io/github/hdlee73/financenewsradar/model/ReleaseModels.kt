package io.github.hdlee73.financenewsradar.model

import java.time.LocalDate

enum class AgencyGroup(val label: String) {
    PRESS("금융당국 보도자료"),
    RESEARCH("금융관련 리포트")
}

/** 보도자료·보고서를 가져오는 기관. [homeUrl]은 기관 목록 페이지(사이트에서 직접 보기용). */
enum class AgencyId(val label: String, val shortLabel: String, val group: AgencyGroup, val homeUrl: String, val itemNoun: String) {
    FSS("금융감독원", "금융감독원", AgencyGroup.PRESS, "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218", "보도자료"),
    FSC("금융위원회", "금융위원회", AgencyGroup.PRESS, "https://www.fsc.go.kr/no010101", "보도자료"),
    MOEF("재정경제부", "재정경제부", AgencyGroup.PRESS, "https://www.mofe.go.kr/nw/nes/nesdta.do?bbsId=MOSFBBS_000000000028&menuNo=4010100", "보도자료"),
    BOK_PRESS("한국은행", "한국은행", AgencyGroup.PRESS, "https://www.bok.or.kr/portal/singl/newsData/list.do?targetDepth=3&menuNo=201263&depth2=200038&depth3=201263", "보도자료"),
    SEC("미국 SEC", "SEC", AgencyGroup.PRESS, "https://www.sec.gov/newsroom", "보도자료"),
    KCMI("자본시장연구원", "자본시장연구원", AgencyGroup.RESEARCH, "https://www.kcmi.re.kr/report/report_list", "보고서"),
    KIF("한국금융연구원", "금융연구원", AgencyGroup.RESEARCH, "https://www.kif.re.kr/kif4/publication/pub_list?mid=10", "보고서"),
    BOK_RESEARCH("한국은행", "한국은행", AgencyGroup.RESEARCH, "https://www.bok.or.kr/portal/singl/newsData/list.do?targetDepth=2&menuNo=201156&depth2=201156", "보고서"),
    IOSCO("IOSCO", "IOSCO", AgencyGroup.RESEARCH, "https://www.iosco.org/publications/?subsection=public_reports", "보고서"),
    FSB("FSB", "FSB", AgencyGroup.RESEARCH, "https://www.fsb.org/publications/", "보고서"),
    BCBS("바젤은행감독위원회(BIS)", "BCBS", AgencyGroup.RESEARCH, "https://www.bis.org/publications", "보고서"),
    ESMA("ESMA", "ESMA", AgencyGroup.RESEARCH, "https://www.esma.europa.eu/databases-library/esma-library", "보고서"),
    FCA("영국 금융행위감독청", "FCA", AgencyGroup.RESEARCH, "https://www.fca.org.uk/research", "보고서"),
    MAS("싱가포르 통화청", "MAS", AgencyGroup.RESEARCH, "https://www.mas.gov.sg/publications", "보고서"),
    /** 사용자가 직접 추가한 연구소(이름·주소는 [CustomInstitute]). */
    CUSTOM("추가한 연구소", "추가", AgencyGroup.RESEARCH, "", "자료"),
    /** 사용자가 직접 추가한 보도자료 기관. */
    CUSTOM_PRESS("추가한 기관", "추가", AgencyGroup.PRESS, "", "보도자료");

    /** 처음 보여 줄 최근 자료 수: 보도자료·연구자료 모두 20건. */
    val latestCount: Int get() = 20
}

data class ReleaseItem(
    val agency: AgencyId,
    val title: String,
    val link: String,
    val date: LocalDate? = null,
    /** 사용자가 추가한 연구소의 이름(내장 기관이면 null). */
    val sourceLabel: String? = null,
    /** 보고서 저자(알 수 있을 때만). */
    val author: String? = null
) {
    val label: String get() = sourceLabel ?: agency.shortLabel
}

/** 사용자가 추가한 기관. [group]이 PRESS면 보도자료 화면, RESEARCH면 연구자료 화면에 나온다. */
data class CustomInstitute(val name: String, val url: String, val group: AgencyGroup = AgencyGroup.RESEARCH) {
    val agency: AgencyId get() = if (group == AgencyGroup.PRESS) AgencyId.CUSTOM_PRESS else AgencyId.CUSTOM
}

data class ReleasePage(
    val items: List<ReleaseItem>,
    val hasMore: Boolean = false,
    val nextPage: Int = 1
)

data class UsefulLink(val name: String, val url: String, val description: String = "") {
    companion object {
        /** 기본 사이트의 한 줄 설명. 주소에 이 낱말이 들어 있으면 같은 사이트로 보며, 더 긴(구체적인) 주소부터 맞춘다. */
        private val DEFAULT_DESCRIPTIONS = listOf(
            "fss.or.kr" to "금융감독·검사 업무, 보도자료와 제재·공시 정보를 제공합니다.",
            "fsc.go.kr" to "금융정책·규제, 보도자료와 법령해석 정보를 제공합니다.",
            "dart.fss.or.kr" to "상장·등록법인의 사업보고서 등 기업 공시를 조회합니다.",
            "sec.gov" to "미국 SEC에 제출된 상장기업 공시(10-K·10-Q 등)를 조회합니다.",
            "fine.fss.or.kr" to "금융소비자를 위한 금융상품 비교와 금융회사 정보를 제공합니다.",
            "krx.co.kr" to "주식·파생상품 시장 정보와 상장·시장감시 안내를 제공합니다.",
            "law.go.kr" to "법령·행정규칙·판례 등 법령정보를 검색합니다.",
            "freesis.kofia.or.kr" to "금융투자업권 통계와 시장 현황 자료를 제공합니다.",
            "kofiabond.or.kr" to "채권 시가평가·민평 금리 등 채권시장 정보를 제공합니다.",
            "dis.kofia.or.kr" to "금융투자회사 공시와 협회 규정·통계를 조회합니다.",
            "seibro.or.kr" to "증권 예탁·결제, 주식·채권 발행과 권리행사 정보를 제공합니다.",
            "kcmi.re.kr" to "자본시장 정책·제도 연구보고서와 이슈 분석을 제공합니다.",
            "kif.re.kr" to "금융·거시경제 연구보고서와 금융 전망을 제공합니다.",
            "iosco.org" to "국제증권관리위원회기구(IOSCO)의 보고서와 규제 기준을 제공합니다.",
            "bis.org" to "국제결제은행(BIS)과 바젤위원회의 간행물·보고서를 제공합니다.",
            "fca.org.uk" to "영국 금융행위감독청(FCA)의 연구·정책 자료를 제공합니다.",
            "mas.gov.sg" to "싱가포르 통화청(MAS)의 간행물과 감독 자료를 제공합니다."
        )

        /** [url]에 해당하는 기본 설명. 모르는 사이트면 빈 문자열. */
        fun defaultDescription(url: String): String =
            DEFAULT_DESCRIPTIONS.sortedByDescending { it.first.length }.firstOrNull { url.contains(it.first, ignoreCase = true) }?.second.orEmpty()

        private fun site(name: String, url: String) = UsefulLink(name, url, defaultDescription(url))

        /** 처음 설치할 때 기본으로 들어가는 사이트. 사용자가 추가·수정·삭제할 수 있다. */
        val DEFAULTS = listOf(
            site("금융감독원", "https://www.fss.or.kr"),
            site("금융위원회", "https://www.fsc.go.kr"),
            site("전자공시시스템 DART", "https://dart.fss.or.kr"),
            site("미국 공시시스템 EDGAR", "https://www.sec.gov/edgar/search/"),
            site("금융소비자 정보포털 파인", "https://fine.fss.or.kr"),
            site("한국거래소", "https://www.krx.co.kr"),
            site("국가법령정보센터(법령정보시스템)", "https://www.law.go.kr"),
            site("금융투자협회 FreeSIS", "https://freesis.kofia.or.kr"),
            site("금융투자협회 채권정보센터", "https://www.kofiabond.or.kr"),
            site("금융투자협회 전자공시서비스", "https://dis.kofia.or.kr"),
            site("예탁결제원 SEIBro", "https://www.seibro.or.kr"),
            site("자본시장연구원", "https://www.kcmi.re.kr"),
            site("한국금융연구원", "https://www.kif.re.kr"),
            site("IOSCO", "https://www.iosco.org")
        )
    }
}
