package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.AdminRuleDocument
import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ArticleNo
import com.hdlee73.everylaw.core.model.ItemPath
import com.hdlee73.everylaw.core.model.LawNode
import com.hdlee73.everylaw.core.model.DelegationTree
import com.hdlee73.everylaw.core.model.KeywordSearchResult
import com.hdlee73.everylaw.core.model.LawQuery
import com.hdlee73.everylaw.core.model.LawSummary
import com.hdlee73.everylaw.core.model.LawVersion
import com.hdlee73.everylaw.core.model.formatYmd
import com.hdlee73.everylaw.core.model.ArticleHit
import com.hdlee73.everylaw.core.model.PrecedentDetail
import com.hdlee73.everylaw.core.model.PrecedentSummary
import com.hdlee73.everylaw.core.parser.LawAliases
import com.hdlee73.everylaw.core.parser.LawQueryParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 사용자에게 그대로 보여줄 수 있는 도메인 오류. */
sealed class LawDomainException(message: String) : Exception(message) {
    class InvalidQuery(message: String) : LawDomainException(message)
    class NotFound(message: String) : LawDomainException(message)
    /** 외부 API 호출·응답 처리 실패. 출처별 원인을 그대로 담는다. */
    class ApiFailure(message: String) : LawDomainException(message)
}

/** 트리 조회 진행 상황. UI가 단계별 로딩 문구를 보여줄 수 있게 Flow로 흘려보낸다. */
sealed interface DelegationProgress {
    data class Step(val message: String) : DelegationProgress
    data class Done(val tree: DelegationTree) : DelegationProgress
}

/**
 * "자본시장법 제12조" → 법률·시행령·시행규칙(3단비교) + 감독규정·시행세칙(행정규칙) + 관련 판례를 하나의 트리로.
 */
class GetDelegationTreeUseCase(
    private val repository: LawRepository,
    private val builder: DelegationTreeBuilder = DelegationTreeBuilder(),
) {
    /**
     * @param asOf 조회 기준 시행일(yyyyMMdd). null 이면 현행 법령 기준.
     */
    operator fun invoke(input: String, asOf: String? = null): Flow<DelegationProgress> = flow {
        val query = when (val parsed = LawQueryParser.parse(input)) {
            is LawQueryParser.Result.Invalid -> throw LawDomainException.InvalidQuery(parsed.reason)
            is LawQueryParser.Result.Ok -> parsed.query
        }

        emit(DelegationProgress.Step("'${query.lawName}' 찾는 중"))
        val candidates = repository.searchLaws(query.lawName)
        // 기준일 조회: 이름이 같은 현행 법령이 없으면(폐지된 구법령 등) 그날 시행 중이던 법령을 연혁에서 찾는다.
        val law = (if (asOf != null) pickLaw(query, candidates, exactOnly = true) ?: historicalLaw(query.lawName, asOf) else null)
            ?: pickLaw(query, candidates)
            ?: throw LawDomainException.NotFound("'${query.lawName}' 법령을 찾지 못했어요.")

        // 병렬 조회(async)에서 함께 쓰므로 동기화 리스트
        val warnings: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        val version = asOf?.let { date ->
            emit(DelegationProgress.Step("${formatYmd(date)} 시행 버전 찾는 중"))
            repository.getLawVersions(law).filter { it.effectiveDate <= date }.maxByOrNull { it.effectiveDate }
                ?: throw LawDomainException.NotFound("${formatYmd(date)}에 시행 중이던 ${law.name}을(를) 찾지 못했어요.")
        }
        if (version != null) warnings += "감독규정·시행세칙과 판례는 현행 기준으로 함께 표시됩니다."

        emit(DelegationProgress.Step("3단 비교로 시행령·시행규칙 매핑 중"))
        val threeTier = runCatching { repository.getThreeTier(law, version?.mst ?: law.mst) }
            .onFailure { warnings += "3단 비교 조회 실패: ${it.message}" }
            .getOrNull()
        val row = threeTier?.rows?.firstOrNull { it.law.number.sameAs(query.article) }

        // 시점 조회는 시행일 법령 본문이 기준(3단비교 본문은 보조), 현행 조회는 3단비교 본문을 우선 사용
        val fetched = suspend {
            runCatching {
                if (version != null) repository.getLawArticlesAt(version, query.article)
                else repository.getLawArticles(law, query.article)
            }.getOrDefault(emptyList()).firstOrNull { it.number.sameAs(query.article) }
        }
        val fromRow = row?.law?.takeIf { it.content.isNotBlank() }
        // 항·호·목으로 나눌 수 있게 줄 구조가 살아 있는 본문 조회를 우선하고, 실패하면 3단 비교 본문을 쓴다.
        val lawArticle: Article = (fetched() ?: fromRow)
            ?: throw LawDomainException.NotFound("${law.name}에 ${query.article.display}가 없어요.")

        // 3단 비교가 시행령·시행규칙을 돌려주지 않으면, 시행령·시행규칙 본문에서 "법 제N조"를 인용한 조문을 직접 찾는다.
        var decrees = row?.decrees.orEmpty()
        var rules = row?.rules.orEmpty()
        var decreeName = threeTier?.decreeName
        var ruleName = threeTier?.ruleName
        if (decrees.isEmpty()) {
            emit(DelegationProgress.Step("시행령 본문에서 위임 조문 찾는 중"))
            val found = findCitingArticles(decreeName ?: "${law.name} 시행령", version == null, warnings) { text ->
                DelegationTreeBuilder.refersTo(text, DelegationTreeBuilder.LAW_PREFIX, query.article)
            }
            if (found != null && found.second.isNotEmpty()) {
                decreeName = found.first
                decrees = found.second
                warnings += "3단 비교에서 시행령 연결을 받지 못해, 시행령 본문의 '법 ${query.article.display}' 인용으로 찾았습니다."
            }
        }
        if (rules.isEmpty()) {
            emit(DelegationProgress.Step("시행규칙 본문에서 위임 조문 찾는 중"))
            val decreeNos = decrees.mapNotNull { it.number }
            val found = findCitingArticles(ruleName ?: "${law.name} 시행규칙", version == null, warnings) { text ->
                DelegationTreeBuilder.refersTo(text, DelegationTreeBuilder.LAW_PREFIX, query.article) ||
                    decreeNos.any { DelegationTreeBuilder.refersTo(text, DelegationTreeBuilder.DECREE_PREFIX, it) }
            }
            if (found != null && found.second.isNotEmpty()) {
                ruleName = found.first
                rules = found.second
            }
        }

        emit(DelegationProgress.Step("감독규정·시행세칙과 판례 찾는 중"))
        val (supervisory, details, precedentResult) = coroutineScope {
            val names = async { relatedAdminRuleNames(law) }
            val prec = async { findRelevantPrecedents(law, query.article, warnings) }
            val (supNames, detNames) = names.await()
            val sup = async { loadAdminRules(supNames, warnings) }
            val det = async { loadAdminRules(detNames, warnings) }
            Triple(sup.await(), det.await(), prec.await())
        }

        var root = builder.build(
            DelegationTreeBuilder.Input(
                lawName = law.name,
                lawArticle = lawArticle,
                decreeName = decreeName,
                decrees = decrees,
                ruleName = ruleName,
                rules = rules,
                supervisory = supervisory,
                details = details,
            )
        )
        if (!query.path.isEmpty) root = focusOn(root, query, warnings)

        emit(
            DelegationProgress.Done(
                DelegationTree(
                    query = query,
                    law = law,
                    root = root,
                    asOf = asOf,
                    version = version,
                    precedents = precedentResult.first,
                    precedentBasis = precedentResult.second,
                    isSample = repository.isSample,
                    warnings = warnings.toList(),
                )
            )
        )
    }

    /**
     * 판례 검색 결과(본문 검색이라 범위가 넓다)에서, 판례 본문이 이 법령의 이 조문을 실제로 인용한 것만 고른다.
     * 후보의 본문을 받아 [PrecedentRelevance]로 판정하고, 인용 위치(참조조문 > 판결요지 > 본문)와 선고일 순으로 정렬한다.
     * 본문을 하나도 받지 못하면 걸러내지 못한 것이므로 검색 결과 상위만 그대로 보여 주고 그 사실을 알린다.
     */
    private suspend fun findRelevantPrecedents(
        law: LawSummary,
        article: ArticleNo,
        warnings: MutableList<String>,
    ): Pair<List<PrecedentSummary>, String> {
        val target = "${law.name} ${article.display}"
        val candidates = runCatching { repository.searchPrecedents(target) }
            .onFailure { warnings += "판례 조회 실패: ${it.message}" }
            .getOrDefault(emptyList())
            .take(MAX_PREC_CANDIDATES)
        if (repository.isSample) return candidates.take(10) to "데모 데이터라 관련도를 가리지 않고 예시 판례를 보여 드려요."
        if (candidates.isEmpty()) return emptyList<PrecedentSummary>() to "'$target'을(를) 본문에서 찾은 판례가 없어요."

        val names = (listOfNotNull(law.name, law.abbreviation) + LawAliases.namesFor(law.name)).distinct()
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val checked = coroutineScope {
            candidates.map { p ->
                async {
                    gate.withPermit {
                        val detail = runCatching { repository.getPrecedent(p.serialNo) }.getOrNull()
                        detail?.let { p to PrecedentRelevance.where(it, names, article) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (checked.isEmpty()) {
            return candidates.take(10) to "판례 본문을 확인하지 못해 관련도를 가리지 못했어요. 검색 결과 최근 ${minOf(10, candidates.size)}건을 그대로 보여 드려요."
        }
        val picked = checked.mapNotNull { (p, where) -> where?.let { p.copy(matchNote = it.label) to it } }
            .sortedWith(compareByDescending<Pair<PrecedentSummary, PrecedentRelevance.Where>> { it.second.rank }.thenByDescending { it.first.decisionDate.orEmpty() })
            .map { it.first }
            .take(MAX_PRECEDENTS_SHOWN)
        val basis = "판례 본문 검색 결과 ${candidates.size}건 중, 본문에서 '${law.name}'(약칭 포함) 바로 뒤에 ${article.display}를 인용한 ${picked.size}건만 골랐어요. " +
            "참조조문 → 판결요지 → 본문 순으로, 같은 순서면 최근 선고순이에요. 다른 법령의 같은 조 번호 인용은 제외했어요."
        return picked to basis
    }

    /** 항·호·목까지 지정한 검색: 그 부분의 본문과, 그 위치로 연결된 하위 규정만 남긴다. */
    private fun focusOn(root: LawNode, query: LawQuery, warnings: MutableList<String>): LawNode {
        val parsed = root.article.parsed
        val target = parsed.find(query.path)
            ?: throw LawDomainException.NotFound("${root.sourceName} ${query.article.display}에 ${query.path.display}이(가) 없어요.")
        val body = buildString {
            if (parsed.header.isNotBlank()) append(parsed.header).append('\n')
            // 호·목을 지정했으면 문맥을 위해 상위 항의 첫 줄도 함께 보여 준다.
            if (query.path.subparagraph != null && query.path.paragraph != null) {
                parsed.find(ItemPath(query.path.paragraph))?.let { append(it.text.lines().first()).append('\n') }
            }
            target.flatten().forEach { append(it.text).append('\n') }
        }.trim()
        val (kept, note) = selectForPath(root.withParagraphPieces().children, query.path)
        note?.let { warnings += it }
        return root.copy(article = root.article.copy(content = body), children = kept)
    }

    /** 이름이 [name]인 법령(시행령·시행규칙) 본문에서 [matches]에 맞는 조문을 모은다. 실패하면 null. */
    private suspend fun findCitingArticles(
        name: String,
        current: Boolean,
        warnings: MutableList<String>,
        matches: (String) -> Boolean,
    ): Pair<String, List<Article>>? {
        if (!current) return null
        val compact = name.replace(" ", "")
        return runCatching {
            val hit = repository.searchLaws(name).let { list ->
                list.firstOrNull { it.name.replace(" ", "") == compact }
            } ?: return@runCatching null
            val articles = repository.getLawArticles(hit).filter { it.number != null && matches(it.content) }
            hit.name to articles.take(MAX_FALLBACK_ARTICLES)
        }.onFailure { warnings += "'$name' 본문 조회 실패: ${it.message}" }.getOrNull()
    }

    private suspend fun historicalLaw(name: String, asOf: String): LawSummary? {
        val compact = LawAliases.resolve(name).replace(" ", "")
        val v = runCatching { repository.searchLawVersionsAsOf(LawAliases.resolve(name), asOf) }.getOrDefault(emptyList())
            .firstOrNull { it.name.replace(" ", "") == compact } ?: return null
        return LawSummary(v.mst, v.mst, v.name, null, "법률", v.effectiveDate)
    }

    private fun pickLaw(query: LawQuery, candidates: List<LawSummary>, exactOnly: Boolean = false): LawSummary? {
        val compact = query.lawName.replace(" ", "")
        return candidates.firstOrNull { it.name.replace(" ", "") == compact }
            ?: candidates.firstOrNull { it.abbreviation?.replace(" ", "") == compact }
            ?: candidates.takeIf { !exactOnly }?.firstOrNull { it.kind == "법률" }
            ?: candidates.takeIf { !exactOnly }?.firstOrNull()
    }

    /** (감독규정 이름들, 시행세칙 이름들) */
    private suspend fun relatedAdminRuleNames(law: LawSummary): Pair<List<String>, List<String>> {
        val fromHierarchy = runCatching { repository.getRelatedAdminRuleNames(law) }.getOrDefault(emptyList())
        val details = (LawAliases.enforcementDetails(law.name) + fromHierarchy.filter { it.contains("세칙") }).distinct()
        val supervisory = (LawAliases.supervisoryRegulations(law.name) +
            fromHierarchy.filter { it.contains("규정") && !it.contains("세칙") }).distinct()
        return supervisory.take(MAX_ADMIN_DOCS) to details.take(MAX_ADMIN_DOCS)
    }

    private suspend fun loadAdminRules(names: List<String>, warnings: MutableList<String>): List<AdminRuleDocument> =
        coroutineScope {
            names.map { name ->
                async {
                    runCatching {
                        val hit = repository.searchAdminRules(name).let { list ->
                            list.firstOrNull { it.name.replace(" ", "") == name.replace(" ", "") } ?: list.firstOrNull()
                        } ?: return@runCatching null
                        repository.getAdminRule(hit.serialNo).let { if (it.name.isBlank()) it.copy(name = hit.name, agency = hit.agency) else it }
                    }.onFailure { warnings += "'$name' 조회 실패: ${it.message}" }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

    companion object {
        const val MAX_ADMIN_DOCS = 3
        const val MAX_FALLBACK_ARTICLES = 30
        const val MAX_PREC_CANDIDATES = 40
        const val MAX_PRECEDENTS_SHOWN = 20
    }
}

private fun ArticleNo?.sameAs(other: ArticleNo): Boolean =
    this != null && main.trimStart('0') == other.main.trimStart('0') && (branch ?: 0) == (other.branch ?: 0)

/**
 * 법령·행정규칙·판례를 한 번에 찾는 키워드 검색. 일부가 실패해도 나머지 결과는 보여준다.
 *
 * - "자본시장법 손실보전"처럼 앞에 법령명을 붙이면 그 법령 안에서 조문 제목·내용을 찾는다.
 * - 키워드만 입력하면 법령 본문 검색에서 나온 상위 법령 안에서도 조문 제목·내용을 찾아 함께 보여 준다.
 */
class SearchKeywordUseCase(private val repository: LawRepository) {
    /** @param asOf 기준일(yyyyMMdd). 주면 그날 시행 중이던 법령(폐지된 구법령 포함)에서 조문을 찾는다. */
    suspend operator fun invoke(keyword: String, asOf: String? = null): KeywordSearchResult {
        val k = keyword.trim()
        if (k.length < 2) throw LawDomainException.InvalidQuery("검색어를 두 글자 이상 입력해 주세요.")
        scoped(k, asOf)?.let { return it }
        return coroutineScope {
            val laws = async { runCatching { repository.searchLaws(k, bodySearch = true) } }
            val rules = async { runCatching { repository.searchAdminRules(k) } }
            val precs = async { runCatching { repository.searchPrecedents(k) } }
            val lawsR = laws.await()
            val rulesR = rules.await()
            val precsR = precs.await()
            val failures = listOf("법령" to lawsR, "행정규칙" to rulesR, "판례" to precsR).mapNotNull { (label, r) ->
                r.exceptionOrNull()?.let { "$label 검색 실패 · ${describe(it)}" }
            }
            if (failures.size == 3) throw LawDomainException.ApiFailure(failures.joinToString("\n"))
            val lawList = lawsR.getOrDefault(emptyList())
            val scan = if (asOf != null) scanAsOf(k, asOf, lawList) else scanUnscoped(k, lawList)
            val hits = scan.hits
            // 기준일 검색: 법령·행정규칙 목록은 현행 기준이라 그 시점과 맞지 않으므로 보여 주지 않는다.
            val asOfNote = if (asOf == null) emptyList() else listOf(
                "기준일(${formatYmd(asOf)}) 검색: 조문은 그날 시행 중이던 법령에서 찾았어요. 법령·행정규칙 목록은 현행 기준이라 제외했고 판례는 현행 검색 그대로예요.",
            )
            KeywordSearchResult(
                keyword = k,
                laws = if (asOf != null) emptyList() else lawList,
                adminRules = if (asOf != null) emptyList() else rulesR.getOrDefault(emptyList()),
                precedents = precsR.getOrDefault(emptyList()),
                isSample = repository.isSample,
                warnings = failures + asOfNote,
                articleHits = hits,
                scanNote = scan.note,
            )
        }
    }

    private class Scan(val hits: List<ArticleHit>, val note: String)

    /**
     * 법령명 없이 키워드만 넣었을 때 조문을 찾는다.
     * 본문 검색 결과는 관련도순이 아니라서(가나다순에 가깝다) 앞의 몇 곳만 보면 자본시장법 같은 핵심 법령이 빠진다.
     * 그래서 주요 금융법령과 모법(법률)을 먼저, 동시에 여러 곳을 훑고, 그래도 없으면 나머지 결과와
     * 검색 결과에 빠진 주요 금융법령까지 넓혀 찾는다.
     */
    private suspend fun scanUnscoped(k: String, found: List<LawSummary>): Scan = coroutineScope {
        val gate = Semaphore(SCAN_PARALLEL)
        suspend fun scan(laws: List<LawSummary>): List<ArticleHit> = laws.map { law ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                        runCatching { articleHits(law, listOf(k), PER_LAW_UNSCOPED) }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
            }
        }.awaitAll().flatten()

        fun compact(n: String) = n.replace(" ", "")
        val major = LawAliases.majorLaws().map(::compact).toSet()
        // 법령명에 키워드가 든 법령(본문 검색에는 밀릴 수 있다)을 따로 찾아 맨 앞에 둔다
        val byName = runCatching { repository.searchLaws(k) }.getOrDefault(emptyList()).filter { compact(it.name).contains(compact(k)) }
        val nameIds = byName.map { it.id }.toSet()
        val ordered = (byName + found.filter { it.id !in nameIds }).sortedWith(
            compareBy<LawSummary>({ if (it.id in nameIds) 0 else 1 }, { if (compact(it.name) in major) 0 else 1 }, { if (it.name.endsWith("시행령") || it.name.endsWith("시행규칙")) 1 else 0 }),
        )
        var scanned = ordered.take(MAX_SCAN)
        var hits = scan(scanned)
        var widened = false
        if (hits.isEmpty()) {
            widened = true
            val rest = ordered.drop(MAX_SCAN).take(MAX_WIDEN_REST)
            val missing = LawAliases.majorLaws().filter { m -> ordered.none { compact(it.name) == compact(m) } }.map { name ->
                async {
                    gate.withPermit {
                        runCatching { repository.searchLaws(name) }.getOrDefault(emptyList()).firstOrNull { compact(it.name) == compact(name) }
                    }
                }
            }.awaitAll().filterNotNull()
            val more = rest + missing
            hits = scan(more)
            scanned = scanned + more
        }
        val note = if (scanned.isEmpty()) "" else
            "법령 ${scanned.size}곳을 훑었어요. " + foundIn(hits) +
                (if (widened) " 처음 훑은 곳에 없어 나머지 검색 결과와 주요 금융법령까지 넓혀 봤어요." else "") +
                " 법령명을 앞에 붙이면 그 법 전체에서 찾아요."
        Scan(hits.sortedByDescending { it.inTitle }, note)
    }

    /** "자본시장법 등 3곳(…)에서 조문을 찾았어요." / 없으면 "찾지 못했어요." */
    private fun foundIn(hits: List<ArticleHit>): String {
        val laws = hits.map { it.law.name }.distinct()
        return if (laws.isEmpty()) "조문을 찾지 못했어요." else
            "조문은 ${laws.size}곳(${laws.take(3).joinToString(", ")}${if (laws.size > 3) " 등" else ""})에서 찾았어요."
    }

    private fun asLawSummary(v: LawVersion) = LawSummary(v.mst, v.mst, v.name, null, null, v.effectiveDate)

    /**
     * 기준일 키워드 검색. 현행 법령 목록은 그 시점에 없던 법(예: 1999년의 자본시장법)을 섞으므로 쓰지 않고,
     * 금융 구법령·주요 금융법령과 본문 검색에 걸린 법령 이름을 연혁(시행일 법령)에서 그날의 버전으로 찾아 훑는다.
     */
    private suspend fun scanAsOf(k: String, asOf: String, bodyResults: List<LawSummary>): Scan = coroutineScope {
        val gate = Semaphore(SCAN_PARALLEL)
        fun compact(n: String) = n.replace(" ", "")
        // 키워드 자체(와 그 낱말)를 법령명 검색어로 먼저 쓴다. 구법령은 이름이 달라(예: "주식회사의 외부감사에 관한 법률")
        // 고정 목록에 없어도 "외부감사"로 연혁을 찾으면 걸린다.
        val nameQueries = (listOf(k) + k.split(Regex("""\s+""")).filter { it.length >= 2 }).distinctBy(::compact)
        val names = (nameQueries + LawAliases.historicalFinancialLaws() + LawAliases.majorLaws() + bodyResults.take(ASOF_BODY_LAWS).map { it.name })
            .distinctBy(::compact)
        fun nameMatches(n: String) = nameQueries.any { compact(n).contains(compact(it)) }
        val versions = names.map { name ->
            async { gate.withPermit { runCatching { repository.searchLawVersionsAsOf(name, asOf) }.getOrDefault(emptyList()) } }
        }.awaitAll().flatten().distinctBy { it.mst }
            // 이름에 키워드가 든 법령 먼저, 그다음 모법(법률)을 시행령·시행규칙보다 먼저, 같은 종류끼리는 위 이름 순서대로
            .sortedWith(compareBy<LawVersion>({ if (nameMatches(it.name)) 0 else 1 }, { if (it.name.endsWith("시행령") || it.name.endsWith("시행규칙")) 1 else 0 }))

        suspend fun scan(vs: List<LawVersion>): List<ArticleHit> = vs.map { v ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                        runCatching { articleHits(asLawSummary(v), listOf(k), PER_LAW_UNSCOPED, v) }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
            }
        }.awaitAll().flatten()

        var scanned = versions.take(MAX_ASOF_SCAN)
        var hits = scan(scanned)
        var widened = false
        if (hits.isEmpty() && versions.size > MAX_ASOF_SCAN) {
            widened = true
            val more = versions.drop(MAX_ASOF_SCAN).take(MAX_ASOF_WIDEN)
            hits = scan(more)
            scanned = scanned + more
        }
        val note = if (scanned.isEmpty()) {
            "${formatYmd(asOf)}에 시행 중이던 법령을 연혁에서 찾지 못했어요. 법령명을 앞에 붙여 보세요(예: 증권거래법 ${k})."
        } else {
            "${formatYmd(asOf)} 시행 법령 ${scanned.size}곳을 훑었어요. " + foundIn(hits) +
                (if (widened) " 처음 훑은 곳에 없어 더 넓혀 봤어요." else "") +
                " 법령명을 앞에 붙이면 그 법 전체에서 찾아요."
        }
        Scan(hits.sortedByDescending { it.inTitle }, note)
    }

    /** "법령명 키워드…" 형태면 그 법령으로 범위를 좁힌 결과, 아니면 null. */
    private suspend fun scoped(k: String, asOf: String?): KeywordSearchResult? {
        val tokens = k.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        for (i in tokens.size - 1 downTo 1) {
            val prefix = tokens.take(i).joinToString(" ")
            if (!looksLikeLawName(prefix)) continue
            val resolved = LawAliases.resolve(prefix)
            val compact = resolved.replace(" ", "")
            // 기준일이 있으면 그날 시행 중이던 버전(폐지된 구법령 포함)에서 찾는다.
            val (law, version) = if (asOf != null) {
                val vs = runCatching { repository.searchLawVersionsAsOf(resolved, asOf) }.getOrDefault(emptyList())
                val v = vs.firstOrNull { it.name.replace(" ", "") == compact }
                    ?: vs.firstOrNull { it.name.replace(" ", "").contains(compact) }
                    ?: continue
                asLawSummary(v) to v
            } else {
                val found = runCatching { repository.searchLaws(resolved) }.getOrDefault(emptyList())
                val l = found.firstOrNull { it.name.replace(" ", "") == compact }
                    ?: found.firstOrNull { it.name.replace(" ", "").contains(compact) }
                    ?: continue
                l to null
            }
            val words = tokens.drop(i)
            return coroutineScope {
                val hits = async { runCatching { articleHits(law, words, MAX_SCOPED_HITS, version) } }
                val precs = async { runCatching { repository.searchPrecedents("${law.name} ${words.joinToString(" ")}") } }
                val hitsR = hits.await()
                val warnings = listOfNotNull(
                    hitsR.exceptionOrNull()?.let { "조문 검색 실패 · ${describe(it)}" },
                    precs.await().exceptionOrNull()?.let { "판례 검색 실패 · ${describe(it)}" },
                )
                KeywordSearchResult(
                    keyword = k,
                    laws = listOf(law),
                    adminRules = emptyList(),
                    precedents = precs.await().getOrDefault(emptyList()),
                    isSample = repository.isSample,
                    warnings = warnings,
                    articleHits = hitsR.getOrDefault(emptyList()),
                    scopeLaw = law,
                )
            }
        }
        return null
    }

    private fun looksLikeLawName(s: String): Boolean {
        val c = s.replace(" ", "")
        return LawAliases.isKnown(s) || c.endsWith("법") || c.endsWith("법률") || c.endsWith("령")
    }

    /** 법령의 조문 중 제목·본문에 [words]가 모두 들어 있는 것. 제목에 들어 있는 조문을 먼저, 그 안에서는 조문 순서대로. */
    private suspend fun articleHits(law: LawSummary, words: List<String>, limit: Int, version: LawVersion? = null): List<ArticleHit> {
        val articles = (if (version != null) repository.getLawArticlesAt(version) else repository.getLawArticles(law)).filter { it.number != null }
        return articles.mapNotNull { a ->
            val inTitle = words.all { a.title.contains(it) }
            val inBody = words.all { (a.title + "\n" + a.content).contains(it) }
            if (!inTitle && !inBody) null else ArticleHit(law, a, inTitle, snippet(a, words))
        }.sortedByDescending { it.inTitle }.take(limit)
    }

    private fun snippet(a: Article, words: List<String>): String {
        val line = a.content.lines().firstOrNull { l -> words.any { l.contains(it) } } ?: return ""
        val at = line.indexOf(words.first { line.contains(it) })
        val from = (at - 20).coerceAtLeast(0)
        return (if (from > 0) "…" else "") + line.substring(from, minOf(line.length, from + 80)).trim() + if (from + 80 < line.length) "…" else ""
    }

    private fun describe(e: Throwable): String =
        "${e::class.simpleName}: ${e.message?.takeIf { it.isNotBlank() } ?: "(메시지 없음)"}"

    companion object {
        const val MAX_SCAN = 8
        const val MAX_ASOF_SCAN = 12
        const val MAX_ASOF_WIDEN = 16
        const val MAX_WIDEN_REST = 8
        const val ASOF_BODY_LAWS = 12
        const val SCAN_PARALLEL = 4
        const val SCAN_TIMEOUT_MS = 30_000L
        const val PER_LAW_UNSCOPED = 8
        const val MAX_SCOPED_HITS = 40
    }
}

class GetLawVersionsUseCase(private val repository: LawRepository) {
    suspend operator fun invoke(law: LawSummary): List<LawVersion> = repository.getLawVersions(law)
}

class GetLawArticlesUseCase(private val repository: LawRepository) {
    suspend operator fun invoke(law: LawSummary): List<Article> =
        repository.getLawArticles(law).filter { it.number != null }
}

class GetAdminRuleUseCase(private val repository: LawRepository) {
    suspend operator fun invoke(serialNo: String): AdminRuleDocument = repository.getAdminRule(serialNo)
}

class GetPrecedentUseCase(private val repository: LawRepository) {
    suspend operator fun invoke(serialNo: String): PrecedentDetail = repository.getPrecedent(serialNo)
}

/** PDF 범위 선택에 따라 필요한 판례 본문·관련 조문을 내려받는다. 일부가 실패해도 나머지는 담는다. */
class BuildPdfExtrasUseCase(private val repository: LawRepository) {
    suspend operator fun invoke(
        tree: DelegationTree,
        precedentBodies: Boolean,
        relatedArticles: Boolean,
    ): com.hdlee73.everylaw.core.model.PdfExtras = coroutineScope {
        val precs = async {
            if (!precedentBodies) emptyList()
            else tree.precedents.take(MAX_PRECEDENTS).map { p ->
                async { runCatching { repository.getPrecedent(p.serialNo) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        val related = async {
            if (!relatedArticles) emptyList() else loadRelated(tree)
        }
        com.hdlee73.everylaw.core.model.PdfExtras(precs.await(), related.await())
    }

    /** 법률 조문 본문이 인용한 같은 법의 다른 조문 (자기 자신 제외) */
    private suspend fun loadRelated(tree: DelegationTree): List<Article> {
        val self = tree.query.article
        val ctx = com.hdlee73.everylaw.core.parser.CitationLinker.Context(tree.law.name)
        val numbers = com.hdlee73.everylaw.core.parser.CitationLinker.find(tree.root.article.content, ctx)
            .filter { it.query.startsWith(tree.law.name + " ") }
            .mapNotNull { ArticleNo.parse(it.query.removePrefix(tree.law.name)) }
            .filter { it != self }
            .distinct()
            .take(MAX_RELATED)
        return coroutineScope {
            numbers.map { no ->
                async {
                    runCatching { repository.getLawArticles(tree.law, no).firstOrNull { it.number.sameAs(no) } }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        const val MAX_PRECEDENTS = 10
        const val MAX_RELATED = 8
    }
}

/** 법률 조문의 항·호·목 위치별로 그 위치를 위임 근거로 삼는 하위 규정 노드를 묶는다. 빈 위치 = 조 전체. */
object ItemAttachment {
    /** "④ 삭제 <2024.2.13>", "삭제 〈2024.2.13〉"처럼 내용 없이 삭제 표시만 있는 줄 (앞의 ①·1.·가. 번호는 있어도 된다) */
    private val DELETED = Regex("""^(?:[\u2460-\u2473\u3251-\u325F\u32B1-\u32BF]|\d+(?:의\d+)?\.|[가-힣]\.)?\s*삭제\s*(?:[<〈‹][^>〉›]*[>〉›])?$""")

    fun isDeletedText(text: String): Boolean = DELETED.matches(text.trim())

    /** 삭제된 항·호·목(또는 삭제된 조 전체)에는 위임한 하위 규정을 붙이지 않는다. */
    fun attach(article: Article, children: List<LawNode>): Map<ItemPath, List<LawNode>> {
        val parsed = article.parsed
        val paths = parsed.allPaths()
        val articleDeleted = parsed.items.isEmpty() && isDeletedText(parsed.lead.ifBlank { article.body })
        val deleted = parsed.items.flatMap { it.flatten() }.filter { isDeletedText(it.text) }.map { it.path }.toSet()
        return children.groupBy { c ->
            var p = c.anchor ?: ItemPath()
            while (!p.isEmpty && p != ItemPath.LEAD && p !in paths) p = p.parent()
            p
        }.filterKeys { p -> !articleDeleted && p !in deleted }
    }
}
