package io.github.hdlee73.financenewsradar.model

import java.time.LocalDate

/** 유권해석·비조치·행정지도 조회 화면의 출처. [listUrl]은 사용자가 지정한 목록 주소. */
enum class RegSource(val label: String, val tabLabel: String, val listUrl: String, val isGuidance: Boolean) {
    LAW_INTERPRETATION(
        "금융위원회 법령해석", "유권해석",
        "https://better.fsc.go.kr/fsc_new/replyCase/LawreqList.do?stNo=11&muNo=85&muGpNo=75", false
    ),
    NO_ACTION(
        "금융위원회 비조치의견서", "비조치",
        "https://better.fsc.go.kr/fsc_new/replyCase/OpinionList.do?stNo=11&muNo=86&muGpNo=75", false
    ),
    FSC_GUIDANCE(
        "금융위원회 행정지도", "금융위 행정지도",
        "https://better.fsc.go.kr/fsc_new/status/adminMap/OpertnList.do?stNo=11&muNo=145&muGpNo=60", true
    ),
    FSS_GUIDANCE(
        "금융감독원 행정지도", "금감원 행정지도",
        "https://www.fss.or.kr/fss/job/admnstgudc/list.do?menuNo=200492", true
    );

    /** 처음 가져오는 건수(행정지도는 금융위·금감원 각각). */
    val count: Int get() = 20
}

/**
 * 조회 항목. [date]는 목록의 날짜(법령해석·비조치는 회신일 등, 행정지도는 시행일),
 * [body]는 상세 페이지에서 읽은 내용(전체 검색용), [inForce]는 행정지도의 시행중 여부(알 수 없으면 null).
 */
data class RegItem(
    val source: RegSource,
    val title: String,
    val link: String,
    val date: LocalDate? = null,
    val body: String = "",
    val statusText: String? = null,
    val inForce: Boolean? = null
)
