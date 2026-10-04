package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.AppSettings
import io.github.hdlee73.financenewsradar.model.NaverCredentials
import io.github.hdlee73.financenewsradar.model.NewsArticle
import io.github.hdlee73.financenewsradar.model.NewsProviderType
import io.github.hdlee73.financenewsradar.model.OutletScope
import io.github.hdlee73.financenewsradar.model.SearchPage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

class NewsRepository {
    suspend fun search(
        query: String,
        settings: AppSettings,
        credentials: NaverCredentials,
        start: Int = 1
    ): SearchPage = supervisorScope {
        NewsSourceInfo.reset()
        val provider = provider(settings.provider, credentials)
        val plan = SearchQueryParser.parse(query)
        val results = plan.providerQueries.map { providerQuery ->
            async { runCatching { provider.search(providerQuery, settings.timeRange, start) } }
        }.awaitAll()
        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
            ?: IllegalStateException("기사를 불러오지 못했습니다.")
        val fetched = pages.flatMap { it.articles }
        val refined = refine(fetched, settings, plan.terms)
        SearchPage(
            articles = refined.articles,
            hasMore = pages.any { it.hasMore },
            nextStart = pages.filter { it.hasMore }.maxOfOrNull { it.nextStart } ?: 1,
            fetchedCount = fetched.size,
            duplicateCount = refined.duplicateCount,
            outletExcludedCount = refined.outletExcludedCount,
            sourceNote = if (settings.provider == NewsProviderType.NAVER) NewsSourceInfo.take() else "Google 뉴스",
            failedQueryCount = results.count { it.isFailure } + pages.sumOf { it.failedQueryCount }
        )
    }

    suspend fun home(settings: AppSettings, credentials: NaverCredentials): SearchPage = supervisorScope {
        NewsSourceInfo.reset()
        val provider = provider(settings.provider, credentials)
        val queries = settings.keywords
            .filter { it.isNotBlank() }
            .flatMap { keyword ->
                NewsQueryPlanner.homeQueries(keyword, settings.provider, NewsProxy.naverAvailable(credentials))
            }
            .distinct()
        val results = queries
            .map { query -> async { runCatching { provider.search(query, settings.timeRange, pageSize = 100) } } }
            .awaitAll()
        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
            ?: IllegalStateException("기사를 불러오지 못했습니다.")
        val fetched = pages.flatMap { it.articles }
        val watchTerms = settings.keywords.flatMap { keyword ->
            runCatching { SearchQueryParser.parse(keyword).terms }.getOrDefault(listOf(keyword))
        }.distinct()
        val refined = refine(fetched, settings, watchTerms)
        SearchPage(
            articles = refined.articles,
            fetchedCount = fetched.size,
            duplicateCount = refined.duplicateCount,
            outletExcludedCount = refined.outletExcludedCount,
            sourceNote = if (settings.provider == NewsProviderType.NAVER) NewsSourceInfo.take() else "Google 뉴스",
            failedQueryCount = results.count { it.isFailure } + pages.sumOf { it.failedQueryCount }
        )
    }

    /** 맞춤 키워드별 최근 7일 기사 수(언론 범위와 상관없이 전체). */
    suspend fun trends(settings: AppSettings, credentials: NaverCredentials): List<KeywordTrend> = supervisorScope {
        NewsSourceInfo.reset()
        val provider = provider(settings.provider, credentials)
        settings.keywords.filter { it.isNotBlank() }.map { keyword ->
            async {
                val query = runCatching { SearchQueryParser.parse(keyword).providerQueries.first() }.getOrDefault(keyword)
                runCatching { provider.search(query, io.github.hdlee73.financenewsradar.model.TimeRange.WEEK, 1, KeywordTrend.FETCH_LIMIT) }
                    .fold(
                        onSuccess = { KeywordTrend.compute(keyword, it.articles) },
                        onFailure = { KeywordTrend(keyword, List(KeywordTrend.DAYS) { 0 }, false, it.message ?: "실패") }
                    )
            }
        }.awaitAll()
    }

    private fun provider(type: NewsProviderType, credentials: NaverCredentials): NewsProvider = when (type) {
        NewsProviderType.GOOGLE_RSS -> GoogleNewsRssProvider()
        NewsProviderType.NAVER -> NaverFirstNewsProvider(credentials)
    }

    private data class RefinedArticles(
        val articles: List<NewsArticle>,
        val duplicateCount: Int,
        val outletExcludedCount: Int
    )

    private fun refine(
        items: List<NewsArticle>,
        settings: AppSettings,
        keywords: List<String>
    ): RefinedArticles {
        val inScope = items.filter {
            settings.outletScope == OutletScope.ALL || PublisherCatalog.isMajor(it.source, it.link)
        }
        val seenTitles = mutableSetOf<String>()
        val articles = inScope.asSequence()
            .sortedByDescending { it.publishedAt }
            .filter { article ->
                val key = NewsText.normalizeTitle(article.title).ifBlank { article.link }
                seenTitles.add(key)
            }
            .map { article ->
                article.copy(
                    matchedKeywords = NewsText.matchingKeywords(article.title, article.summary, keywords),
                    isPriority = NewsText.isPriority(article.title, article.summary)
                )
            }
            .toList()
        return RefinedArticles(
            articles = articles,
            duplicateCount = inScope.size - articles.size,
            outletExcludedCount = items.size - inScope.size
        )
    }

}

internal object NewsQueryPlanner {
    fun homeQueries(
        keyword: String,
        provider: NewsProviderType,
        naverReady: Boolean = false
    ): List<String> {
        val exact = keyword.trim()
        val plan = runCatching { SearchQueryParser.parse(exact) }.getOrNull()
        val baseQueries = plan?.providerQueries ?: listOf(exact)
        val googleOnly = provider == NewsProviderType.GOOGLE_RSS || !naverReady
        if (!googleOnly || plan?.usesBooleanOperators == true) return baseQueries
        return (baseQueries + expandFinanceQuery(exact)).distinct()
    }

    private fun expandFinanceQuery(keyword: String): String = when (keyword.trim()) {
        "금융감독원", "금감원" -> "(금융감독원 OR 금감원) (증권 OR 자산운용 OR 금융투자 OR 펀드)"
        "자산운용사", "운용사" -> "(자산운용사 OR 운용사) (금감원 OR 금융감독 OR 펀드)"
        "증권사" -> "증권사 (금감원 OR 검사 OR 제재 OR 내부통제 OR 금융사고)"
        "금융투자" -> "금융투자 (금감원 OR 검사 OR 제재 OR 내부통제 OR 금융사고)"
        "금융사고" -> "금융사고 (증권사 OR 운용사 OR 금융투자)"
        else -> keyword
    }
}
