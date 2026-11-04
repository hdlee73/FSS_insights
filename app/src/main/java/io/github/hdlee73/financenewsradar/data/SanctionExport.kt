package io.github.hdlee73.financenewsradar.data

import java.io.OutputStream
import java.time.format.DateTimeFormatter

/** 제재공시 세부내역·통계를 엑셀로 저장한다. */
object SanctionExport {
    private val ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    const val NOTICE = "2016년 이후 금융감독원 '금융회사 제재공시'의 공개 문서를 자동으로 읽어 정리한 참고용 자료입니다. " +
        "원문과 다르거나 누락·오분류가 있을 수 있으며, 권역은 회사명·담당부서로 추정했습니다. " +
        "과태료·과징금은 공시(건) 단위 금액입니다. 업무에 인용할 때는 반드시 금감원 원문을 확인하세요."

    private fun detailSheet(rows: List<SanctionRow>) = XlsxWriter.Sheet(
        "세부내역",
        listOf("권역", "회사명", "조치요구일", "지적제목", "지적구분", "기관 조치", "임원 조치", "직원 조치",
            "과태료(원, 건 전체)", "과징금(원, 건 전체)", "위반 법률·조항", "담당부서", "공시 페이지", "원문 파일"),
        rows.map { r ->
            val c = r.case
            listOf(
                c.sector, c.company, c.date.format(ymd), r.title, r.item?.category.orEmpty(),
                c.inst, c.exec, c.staff,
                c.fine.takeIf { it > 0 }, c.penalty.takeIf { it > 0 },
                r.laws.joinToString(", "), c.dept, c.viewUrl, c.fileUrl.orEmpty()
            )
        },
        widths = listOf(9, 24, 11, 48, 9, 32, 28, 32, 15, 15, 40, 16, 30, 30)
    )

    private fun statsSheet(s: SanctionStats.Summary, scope: String, generatedAt: String): XlsxWriter.Sheet {
        val rows = mutableListOf<List<Any?>>()
        fun block(title: String, pairs: List<Pair<Any, Number>>) {
            rows += listOf<Any?>(title, null)
            pairs.forEach { (k, v) -> rows += listOf<Any?>(k.toString(), v) }
            rows += listOf<Any?>(null, null)
        }
        rows += listOf<Any?>("범위(기간·조건)", scope)
        rows += listOf<Any?>("자료 기준", generatedAt)
        rows += listOf<Any?>("공시 건수", s.caseCount)
        rows += listOf<Any?>("지적사항 건수", s.itemCount)
        rows += listOf<Any?>("과태료 합계(원)", s.fine)
        rows += listOf<Any?>("과징금 합계(원)", s.penalty)
        rows += listOf<Any?>(null, null)
        block("연도별 공시 건수", s.byYear.map { it.first to it.second })
        if (s.byMonth.size in 1..120) block("월별 공시 건수", s.byMonth.map { "%d.%02d".format(it.first.year, it.first.monthValue) to it.second })
        block("권역별 공시 건수", s.bySector.map { it.first to it.second })
        block("기관 조치 종류별 공시 건수", s.instKinds.map { it.first to it.second })
        block("임원 조치 종류별 공시 건수", s.execKinds.map { it.first to it.second })
        block("직원 조치 종류별 공시 건수", s.staffKinds.map { it.first to it.second })
        block("위반 법률 상위(지적 건수)", s.topLawNames.map { it.first to it.second })
        block("위반 조항 상위(지적 건수)", s.topLaws.map { it.first to it.second })
        block("제재 공시가 많은 회사", s.topCompanies.map { it.first to it.second })
        block("금전제재 상위(원)", s.topMoney.map { "${it.company} (${it.date.format(ymd)})" to it.money })
        rows += listOf<Any?>("유의사항", NOTICE)
        return XlsxWriter.Sheet("통계", listOf("항목", "값"), rows, widths = listOf(46, 60))
    }

    fun write(out: OutputStream, rows: List<SanctionRow>, summary: SanctionStats.Summary, scope: String, generatedAt: String) {
        XlsxWriter.write(out, listOf(detailSheet(rows), statsSheet(summary, scope, generatedAt)))
    }
}
