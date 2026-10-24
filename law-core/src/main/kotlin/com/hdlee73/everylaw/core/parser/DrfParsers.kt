package com.hdlee73.everylaw.core.parser

import com.hdlee73.everylaw.core.model.AdminRuleDocument
import com.hdlee73.everylaw.core.model.AdminRuleSummary
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion
import com.hdlee73.everylaw.core.model.PrecedentDetail
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.model.ThreeTierComparison
import com.hdlee73.everylaw.core.model.ThreeTierRow

/** DRF가 키 오류 등을 HTML/텍스트로 돌려줄 때 던진다. */
class DrfFormatException(message: String) : Exception(message)

/**
 * 국가법령정보 공동활용 DRF 응답(XML) 파서 모음.
 * 태그명은 open.law.go.kr 의 "가이드 > 출력 결과 필드" 표기를 따르되, 비슷한 이름도 함께 찾는다.
 */
object DrfParsers {

    private fun root(xml: String): XmlNode {
        val trimmed = xml.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!trimmed.startsWith("<")) throw DrfFormatException(trimmed.take(200))
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            throw DrfFormatException("API가 HTML을 반환했습니다. OC(인증키)·IP 등록 상태를 확인해 주세요.")
        }
        return XmlNode.parse(trimmed)
    }

    /**
     * 목록 응답이 비어 있는데 정상 목록 응답의 표지(totalCnt)도 없다면 오류 응답으로 보고
     * 루트 태그와 본문 일부를 오류 메시지에 담는다. (OC 오류·잘못된 요청 등을 화면에서 바로 알 수 있게)
     */
    private fun <T> List<T>.orDiagnose(root: XmlNode, xml: String): List<T> {
        if (isNotEmpty() || root.first("totalCnt") != null) return this
        val snippet = xml.replace(Regex("\\s+"), " ").take(240)
        throw DrfFormatException("예상과 다른 응답 <${root.name}> — $snippet")
    }

    // ---------- 법령 목록 (target=law) ----------

    fun parseLawSearch(xml: String): List<LawSummary> {
        val r = root(xml)
        return r.findAll("law").mapNotNull { n ->
            val name = n.childText("법령명한글", "법령명") ?: return@mapNotNull null
            LawSummary(
                id = n.childText("법령ID").orEmpty(),
                mst = n.childText("법령일련번호", "MST").orEmpty(),
                name = name,
                abbreviation = n.childText("법령약칭명"),
                kind = n.childText("법령구분명"),
                effectiveDate = n.childText("시행일자"),
            )
        }.orDiagnose(r, xml)
    }

    // ---------- 시행일 법령 목록 (target=eflaw) ----------

    fun parseLawVersions(xml: String): List<LawVersion> =
        root(xml).findAll("law").mapNotNull { n ->
            val ef = n.childText("시행일자") ?: return@mapNotNull null
            LawVersion(
                mst = n.childText("법령일련번호", "MST").orEmpty(),
                name = n.childText("법령명한글", "법령명").orEmpty(),
                effectiveDate = ef,
                promulgationDate = n.childText("공포일자"),
                revisionType = n.childText("제개정구분명"),
                status = n.childText("현행연혁코드"),
            )
        }

    // ---------- 법령 본문 (lawService target=law) ----------

    data class LawBody(val name: String, val articles: List<Article>)

    fun parseLawBody(xml: String): LawBody {
        val r = root(xml)
        val name = r.firstText("법령명_한글", "법령명한글", "법령명").orEmpty()
        val articles = r.findAll("조문단위")
            .filter { (it.childText("조문여부") ?: "조문") == "조문" }
            .map { unit ->
                val no = unit.childText("조문번호")
                val branch = unit.childText("조문가지번호")?.toIntOrNull()?.takeIf { it > 0 }
                val body = bodyLines(unit).joinToString("\n")
                Article(
                    number = no?.let { ArticleNo.of(it, branch) },
                    title = unit.childText("조문제목").orEmpty(),
                    content = body.trim(),
                )
            }
        return LawBody(name, articles)
    }

    /** 항·호·목은 번호(항번호)와 내용(항내용)이 따로 오는데 내용에 번호가 이미 들어 있는 경우가 많아, 겹치지 않게 한 줄씩 만든다. */
    private fun bodyLines(unit: XmlNode): List<String> {
        val lines = mutableListOf<String>()
        fun labelled(label: String?, text: String?): String? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val l = label?.trim().orEmpty()
            return if (l.isEmpty() || t.startsWith(l)) t else "$l $t"
        }
        unit.childText("조문내용")?.let { lines += it.trim() }
        for (hang in unit.children.filter { it.name == "항" }) {
            labelled(hang.childText("항번호"), hang.childText("항내용"))?.let { lines += it }
            for (ho in hang.children.filter { it.name == "호" }) {
                labelled(ho.childText("호번호"), ho.childText("호내용"))?.let { lines += it }
                for (mok in ho.children.filter { it.name == "목" }) {
                    labelled(mok.childText("목번호"), mok.childText("목내용"))?.let { lines += it }
                }
            }
        }
        return lines
    }

    // ---------- 위임조문 3단비교 (lawService target=thdCmp, knd=2) ----------

    fun parseThreeTier(xml: String): ThreeTierComparison {
        val r = root(xml)
        val info = r.first("기본정보") ?: r
        val lawName = info.childText("법령명", "법률명", "법령명한글").orEmpty()
        val decreeName = info.childText("시행령명")
        val ruleName = info.childText("시행규칙명")

        // 법률조문 요소를 직계 자식으로 가진 요소를 "한 줄(row)"로 본다.
        val groups = r.findAll { node -> node.children.any { it.name.contains("법률조문") } }
        val rows = groups.flatMap { group ->
            val laws = group.children.filter { it.name.contains("법률조문") }
            laws.map { lawEl ->
                ThreeTierRow(
                    law = articleOf(lawEl),
                    decrees = group.children.filter { it.name.contains("시행령조문") }.flatMap(::articlesOf),
                    rules = group.children.filter { it.name.contains("시행규칙조문") }.flatMap(::articlesOf),
                )
            }
        }
        return ThreeTierComparison(lawName, decreeName, ruleName, rows)
    }

    /** 조문 요소가 직접 조번호를 갖거나, 그 아래에 여러 조문이 반복될 수 있다. */
    private fun articlesOf(el: XmlNode): List<Article> {
        val hasOwn = el.children.any { it.name in NUMBER_TAGS || it.name in CONTENT_TAGS }
        return if (hasOwn) listOf(articleOf(el))
        else el.children.filter { c -> c.children.any { it.name in NUMBER_TAGS || it.name in CONTENT_TAGS } }
            .map(::articleOf)
    }

    private val NUMBER_TAGS = setOf("조번호", "조문번호")
    private val CONTENT_TAGS = setOf("조내용", "조문내용")

    private fun articleOf(el: XmlNode): Article {
        val no = el.childText("조번호", "조문번호")
        val branch = el.childText("조가지번호", "조문가지번호")?.toIntOrNull()?.takeIf { it > 0 }
        val content = el.childText("조내용", "조문내용") ?: el.deepText()
        // 3단 비교는 조제목에 "제420조(제목)" 전체를 넣어 주기도 해서, 앞의 조 번호는 떼고 (제목)만 쓴다.
        val title = el.childText("조제목", "조문제목")?.let { raw ->
            HEAD.find(raw)?.takeIf { it.range.first == raw.length - raw.trimStart().length }?.groupValues?.get(3) ?: raw
        } ?: titleFromContent(content)
        val number = no?.let { raw -> ArticleNo.parse(raw) ?: ArticleNo.of(raw, branch) }
            ?: ArticleNo.parse(content.take(20))
        return Article(number = number?.let { if (branch != null && it.branch == null) it.copy(branch = branch) else it }, title = title, content = content.trim())
    }

    private val HEAD = Regex("""^\s*제\s*(\d+(?:-\d+)*)\s*조(?:\s*의\s*(\d+))?\s*(?:\(([^)]*)\))?""")

    private fun titleFromContent(content: String): String = HEAD.find(content)?.groupValues?.get(3).orEmpty()

    // ---------- 법령 체계도 (lawService target=lsStmd) ----------

    /** 체계도에 매달린 행정규칙 이름들. 태그 구조가 문서화가 덜 되어 "행정규칙" 하위의 이름 필드를 모두 모은다. */
    fun parseHierarchyAdminRuleNames(xml: String): List<String> {
        val r = root(xml)
        val direct = r.findAll { it.name.contains("행정규칙명") }.map { it.text }
        val nested = r.findAll { it.name.contains("행정규칙") && !it.name.contains("명") }
            .flatMap { it.findAll { c -> c.name == "법령명" || c.name == "명칭" || c.name == "법령명한글" } }
            .map { it.text }
        return (direct + nested).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // ---------- 행정규칙 (target=admrul) ----------

    fun parseAdminRuleSearch(xml: String): List<AdminRuleSummary> {
        val r = root(xml)
        return r.findAll("admrul").mapNotNull { n ->
            val name = n.childText("행정규칙명") ?: return@mapNotNull null
            AdminRuleSummary(
                serialNo = n.childText("행정규칙일련번호", "행정규칙ID").orEmpty(),
                name = name,
                kind = n.childText("행정규칙종류"),
                agency = n.childText("소관부처명", "발령기관명"),
            )
        }.orDiagnose(r, xml)
    }

    fun parseAdminRule(xml: String): AdminRuleDocument {
        val r = root(xml)
        val info = r.first("행정규칙기본정보") ?: r
        val articles = r.findAll("조문내용")
            .map { it.deepText() }
            .flatMap(::splitAdminArticles)
        return AdminRuleDocument(
            serialNo = info.childText("행정규칙일련번호").orEmpty(),
            name = info.childText("행정규칙명").orEmpty(),
            agency = info.childText("소관부처명", "발령기관명"),
            articles = articles,
        )
    }

    /** "제2-3조(대주주의 요건) ① …" 형식의 본문 블록을 조 단위로 나눈다. 장·절 제목은 버린다. */
    fun splitAdminArticles(block: String): List<Article> {
        val starts = Regex("""(?m)^\s*제\s*\d+(?:-\d+)*\s*조(?:\s*의\s*\d+)?\s*\(""").findAll(block).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { i, s ->
            val chunk = block.substring(s, if (i + 1 < starts.size) starts[i + 1] else block.length).trim()
            val m = HEAD.find(chunk)
            Article(
                number = m?.let { ArticleNo(it.groupValues[1], it.groupValues[2].toIntOrNull()) },
                title = m?.groupValues?.get(3).orEmpty(),
                content = chunk,
            )
        }
    }

    // ---------- 판례 (target=prec) ----------

    fun parsePrecedentSearch(xml: String): List<PrecedentSummary> {
        val r = root(xml)
        return r.findAll("prec").mapNotNull { n ->
            val name = n.childText("사건명") ?: return@mapNotNull null
            PrecedentSummary(
                serialNo = n.childText("판례일련번호").orEmpty(),
                caseName = name,
                caseNumber = n.childText("사건번호"),
                decisionDate = n.childText("선고일자"),
                court = n.childText("법원명"),
                caseType = n.childText("사건종류명"),
            )
        }.orDiagnose(r, xml)
    }

    fun parsePrecedent(xml: String): PrecedentDetail {
        val r = root(xml)
        fun t(vararg tags: String) = r.firstText(*tags).orEmpty().cleanBreaks()
        return PrecedentDetail(
            summary = PrecedentSummary(
                serialNo = t("판례정보일련번호", "판례일련번호"),
                caseName = t("사건명"),
                caseNumber = t("사건번호").ifEmpty { null },
                decisionDate = t("선고일자").ifEmpty { null },
                court = t("법원명").ifEmpty { null },
                caseType = t("사건종류명").ifEmpty { null },
            ),
            holdings = t("판시사항"),
            gist = t("판결요지"),
            referencedArticles = t("참조조문"),
            referencedCases = t("참조판례"),
            fullText = t("판례내용"),
        )
    }

    /** 판례 본문은 <br/>이 텍스트로 섞여 온다. */
    private fun String.cleanBreaks(): String =
        replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
}
