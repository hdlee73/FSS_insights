package io.github.hdlee73.financenewsradar.accounting.data

/** 첨부·원문 파일 링크 */
data class FileLink(val name: String, val url: String)

data class Finding(
    val no: Int,
    val title: String,
    val amountWon: Long?,
    val amountNote: String,
    val summary: String,
    val accounts: List<String>,
    val violations: List<String>
)

/** 조치 요약 플래그(금감원 조치내역 문서에서 추출) */
data class AuditFlags(
    val fine: Boolean = false,
    val fineWon: Long? = null,
    val fineFinal: Boolean = false,
    val fineNote: String = "",
    val auditorDesignationYears: Int? = null,
    val issuanceLimitMonths: Int? = null,
    val prosecution: Boolean = false,
    val referral: Boolean = false,
    val correction: Boolean = false,
    val dismissalRec: Boolean = false,
    val dutySuspension: Boolean = false,
    val auditorRestriction: Boolean = false
)

/** 회계감리결과제재 1건(회사 기준) */
data class AuditAction(
    val slno: String,
    val company: String,
    val auditPeriod: String,
    /** yyyyMMdd */
    val actionDate: String,
    val market: String,
    val viewUrl: String,
    val files: List<FileLink>,
    val decisionRound: Int?,
    val fiscalYearEnds: String,
    val industry: String,
    val findings: List<Finding>,
    val actions: List<String>,
    val flags: AuditFlags,
    val violationTypes: List<String>,
    val accounts: List<String>,
    val parseStatus: String
) {
    /** 목록·선택에 쓰는 고유키(공시 번호가 없으면 회사명+조치일) */
    val key: String get() = slno.ifEmpty { "$company|$actionDate" }
    val year: String get() = actionDate.take(4)
    val maxFindingWon: Long? get() = findings.mapNotNull { it.amountWon }.maxOrNull()
}

/** 질의회신·해석·감리지적사례 게시판 색인 1건 */
data class BoardItem(
    val id: String,
    val title: String,
    /** yyyy-MM-dd */
    val date: String,
    val meta: List<String>,
    val url: String,
    val kind: String,
    val kindLabel: String,
    val files: List<FileLink>,
    /** 출처 표기: 원본 기관 / 수집 경로(예: 한국회계기준원 / 회계위키 경유) */
    val source: String = ""
)

/** 기준서 목록 1건(K-IFRS·일반기업회계기준·회계감사기준·내부회계관리제도) */
data class StandardItem(val group: String, val no: String, val label: String, val title: String, val url: String) {
    /** 원본 제정기관과 수집 경로 */
    val source: String get() = when (group) {
        "K-IFRS", "일반기업회계기준" -> "한국회계기준원 / 회계위키 경유"
        "회계감사기준" -> "한국공인회계사회 / 회계위키 경유"
        "내부회계관리제도" -> "내부회계관리제도운영위원회 / 회계위키 경유"
        "기타 기준서" -> "한국회계기준원(KASB·KSSB) / 직접 연결(kasb.or.kr)"
        else -> "회계위키 경유"
    }
}

/** 공시조사결과제재의 위반행위 1건(【제목】 한 덩어리): 사실관계 문장과 관련 법규. */
data class DisclosureViolation(val title: String, val facts: List<String>, val laws: List<String>)

/**
 * 공시조사결과제재(증권선물위원회·금융위원회 공시위반 조치 내역) **회사별 1건**.
 * 게시글·첨부 하나에 회사가 여럿이어도 회사마다 따로 만든 기록이다(첨부의 제재조치내용 표와 제재대상사실을 읽어 나눔).
 */
data class DisclosureCase(
    val id: String,
    val company: String,
    /** 발행인·매출인·신고인 등 역할(없을 수 있음) */
    val role: String,
    /** 금감원이 이름을 ○○○로 가려 공개한 경우 */
    val masked: Boolean,
    /** 제재조치일 yyyy-MM-dd */
    val date: String,
    val postDate: String,
    val round: String,
    val committee: String,
    val actions: List<String>,
    val kinds: List<String>,
    val fineWon: Long?,
    val issueLimitMonths: Int?,
    val violations: List<DisclosureViolation>,
    val laws: List<String>,
    /** 스마트 법령검색에 바로 넣을 수 있는 '법명 제N조' 목록 */
    val lawRefs: List<String>,
    val coCount: Int,
    val postUrl: String,
    val fileName: String,
    val fileUrl: String
) {
    val year: String get() = (date.ifEmpty { postDate }).take(4)
}

data class Dataset<T>(val generatedAt: String, val items: List<T>)

/** 데이터를 어디서 가져왔는지(원격 최신/기기 보관/앱 내장) */
enum class DataSource { REMOTE, CACHE, SEED }
