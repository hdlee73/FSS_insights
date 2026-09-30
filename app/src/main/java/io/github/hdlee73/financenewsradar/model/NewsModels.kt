package io.github.hdlee73.financenewsradar.model

import java.time.Duration
import java.time.Instant

enum class NewsProviderType(val label: String, val description: String) {
    COMBINED("통합 검색", "Google과 네이버를 함께 검색하고 결과를 합침"),
    GOOGLE_RSS("바로 검색", "설정 없이 Google 뉴스 RSS로 검색"),
    NAVER("네이버 심층 검색", "API 키로 최대 1,000건까지 이어보기")
}

enum class OutletScope(val label: String) {
    MAJOR_30("30대 언론"),
    ALL("전체 언론")
}

enum class TimeRange(val label: String, val googleToken: String, val duration: Duration) {
    DAY("24시간", "when:1d", Duration.ofDays(1)),
    WEEK("7일", "when:7d", Duration.ofDays(7)),
    MONTH("30일", "when:30d", Duration.ofDays(30))
}

data class AppSettings(
    val keywords: List<String> = DEFAULT_KEYWORDS,
    val provider: NewsProviderType = NewsProviderType.COMBINED,
    val outletScope: OutletScope = OutletScope.ALL,
    val timeRange: TimeRange = TimeRange.WEEK
) {
    companion object {
        val DEFAULT_KEYWORDS = listOf("금융감독원", "증권사", "자산운용사", "금융투자", "금융사고")
    }
}

data class NaverCredentials(val clientId: String = "", val clientSecret: String = "") {
    val isComplete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

data class NewsArticle(
    val title: String,
    val link: String,
    val source: String,
    val publishedAt: Instant,
    val summary: String,
    val matchedKeywords: List<String> = emptyList(),
    val isPriority: Boolean = false,
    val isBookmarked: Boolean = false
) {
    val stableId: String get() = link.ifBlank { "$source|$title|$publishedAt" }
}

data class SearchPage(
    val articles: List<NewsArticle>,
    val hasMore: Boolean = false,
    val nextStart: Int = 1,
    val fetchedCount: Int = articles.size,
    val duplicateCount: Int = 0,
    val outletExcludedCount: Int = 0,
    val failedQueryCount: Int = 0
)
