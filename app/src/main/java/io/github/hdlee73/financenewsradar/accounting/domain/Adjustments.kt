package io.github.hdlee73.financenewsradar.accounting.domain

/** 과징금 가중·감경 구분(외감규정 [별표 8] 3., 5.) */
enum class FineAdj {
    NONE,
    /** 기본과징금의 50% 이하 가중 */ UP50,
    /** 위반기간 3개 사업연도 초과(사업연도 수 입력) */ UP_YEARS,
    /** 내부회계관리제도 중요한 취약사항: 20% 이하 */ UP20,
    /** 통념상 부족: 50% 이내 */ UP_FREE,
    /** 50% 이하 감경 */ DOWN50,
    /** 30% 이하 감경 */ DOWN30,
    /** 결정금액 70% 이하 감경(통지 前 자진 수정공시·신고 등) */ DOWN70_FINAL
}

data class Reason(val id: String, val label: String, val steps: Int, val fine: FineAdj, val hint: String = "")

/** 시행세칙 [별표 1] Ⅵ. 기본조치의 가중·감경(단계) + 과징금 조정 구분 */
object Reasons {
    val companyUp = listOf(
        Reason("c_repeat", "재위반: 과실 조치 후 3년 내 / 고의·중과실 조치 후 5년 내 고의·중과실 위반", 1, FineAdj.UP50),
        Reason("c_period", "위반기간 3개 사업연도 초과 (고의)", 1, FineAdj.UP_YEARS),
        Reason("c_false", "감리·심사 시 허위자료 제출·자료제출 거부 또는 감사 방해", 1, FineAdj.UP50),
        Reason("c_icfr", "내부회계관리제도 중요한 취약사항", 1, FineAdj.UP20),
        Reason("c_harm", "다수 이해관계자에게 중대한 손해·사회적 물의", 1, FineAdj.UP50),
        Reason("c_turn", "정정 시 당기손실→이익 반전 또는 완전자본잠식 (고의 제외)", 1, FineAdj.UP50),
        Reason("c_64", "최종 규모배수 64배 이상", 1, FineAdj.UP50, "배수가 64배 이상이면 자동 반영"),
        Reason("c_norm", "그 밖에 통념상 위법행위에 비해 조치 수준이 낮은 경우", 1, FineAdj.UP_FREE)
    )
    val companyDown = listOf(
        Reason("c_small", "소규모: 직전 사업연도 말 자산 또는 3개년 평균 매출 1,000억원 미만(상장·대형비상장·금융 제외)", 1, FineAdj.DOWN50),
        Reason("c_comp", "투자자 등 이해관계자 피해를 충분히 보상", 1, FineAdj.DOWN50),
        Reason("c_report", "외감법 제28조 사실을 증권선물위원회에 신고하거나 감사(위원회)·감사인에게 고지", 1, FineAdj.DOWN50, "요건을 모두 갖추면 2단계 감경 가능(아래 별도 선택)"),
        Reason("c_report2", "위 신고·고지 요건 3가지(주도 안 함·정보 미입수·증거 제공)를 모두 갖춤 (추가 1단계)", 1, FineAdj.NONE),
        Reason("c_pre", "심사·감리 통지 前 자진 수정공시", 1, FineAdj.DOWN70_FINAL, "위법동기가 과실이면 2단계"),
        Reason("c_post", "통지 후 1개월 내 자진 수정공시", 1, FineAdj.DOWN30),
        Reason("c_ifrs", "K-IFRS 도입 전후 회계처리 차이로 인한 위반 (비고의)", 1, FineAdj.DOWN30),
        Reason("c_sp", "원인·결과·방법상 정상을 특별히 참작할 사유", 2, FineAdj.DOWN30),
        Reason("c_value", "밸류업 우수기업 선정 장관급 이상 표창 (고의 제외)", 2, FineAdj.NONE),
        Reason("c_ability", "위반자의 현실적 부담능력 등을 고려할 필요", 1, FineAdj.NONE)
    )
    val auditorUp = listOf(
        Reason("a_period", "위반기간 3개 사업연도 초과 (고의)", 1, FineAdj.UP_YEARS),
        Reason("a_false", "위법행위 은폐·축소를 위한 허위자료 제출·자료 거부", 1, FineAdj.UP50),
        Reason("a_64", "최종 규모배수 64배 이상", 1, FineAdj.UP50, "배수가 64배 이상이면 자동 반영"),
        Reason("a_norm", "그 밖에 통념상 조치 수준이 낮은 경우", 1, FineAdj.UP_FREE)
    )
    val auditorDown = listOf(
        Reason("a_small", "소규모: 회사의 자산 또는 3개년 평균 매출 1,000억원 미만(상장·대형비상장·금융 제외)", 1, FineAdj.DOWN50),
        Reason("a_qc", "품질관리기준을 충실히 이행하였다고 판단(품질관리 감리 결과)", 1, FineAdj.NONE),
        Reason("a_comp", "이해관계자 피해를 충분히 보상", 1, FineAdj.DOWN50),
        Reason("a_report", "외감법 제28조 사실을 신고하거나 고지", 1, FineAdj.DOWN50),
        Reason("a_help", "회사의 자진 수정공시를 사전에 적극 조력한 사실이 서류로 인정", 1, FineAdj.DOWN30),
        Reason("a_reissue", "회사에 수정공시를 권고했으나 거부되어 감사의견 변경 후 감사보고서 재발행", 1, FineAdj.DOWN30),
        Reason("a_ifrs", "K-IFRS 도입 전후 회계처리 차이로 인한 위반 (비고의)", 1, FineAdj.DOWN30),
        Reason("a_sp", "원인·결과·방법상 정상을 특별히 참작할 사유", 2, FineAdj.DOWN30),
        Reason("a_ability", "위반자의 현실적 부담능력 등을 고려할 필요", 1, FineAdj.NONE)
    )

    fun netSteps(selected: Set<String>, up: List<Reason>, down: List<Reason>, auto64: Boolean, motive: Motive?): Int {
        val ups = up.filter { it.id in selected || (auto64 && it.id.endsWith("_64")) }.sumOf { it.steps }
        val downs = down.filter { it.id in selected }.sumOf { r ->
            if (r.id == "c_pre" && motive == Motive.NEGLIGENCE) 2 else r.steps
        }
        return ups - downs
    }
}
