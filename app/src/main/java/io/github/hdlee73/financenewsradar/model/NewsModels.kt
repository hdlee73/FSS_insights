package io.github.hdlee73.financenewsradar.model

import java.time.Duration
import java.time.Instant

enum class NewsProviderType(val label: String) {
    NAVER("네이버"),
    GOOGLE_RSS("Google 뉴스")
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
    val provider: NewsProviderType = NewsProviderType.NAVER,
    val outletScope: OutletScope = OutletScope.ALL,
    val timeRange: TimeRange = TimeRange.WEEK
) {
    companion object {
        val DEFAULT_KEYWORDS = listOf(
            "금융감독원", "증권사", "자산운용사", "금융투자", "금융사고",
            "금감원 제재", "불완전판매", "내부통제", "사모펀드", "공매도"
        )
    }
}

enum class NaverApiType(val label: String) {
    API_HUB("NAVER API HUB (신규 키)"),
    DEVELOPERS("개발자센터 (기존 키)")
}

data class NaverCredentials(val clientId: String = "", val clientSecret: String = "", val apiType: NaverApiType = NaverApiType.API_HUB) {
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
    /** 실제로 쓰인 검색 경로 설명(네이버 / Google 대체 사유). */
    val sourceNote: String? = null,
    val failedQueryCount: Int = 0
)
