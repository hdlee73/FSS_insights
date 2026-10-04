package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NewsArticle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 키워드 하나의 최근 7일 기사 수 추이. [daily]는 6일 전 → 오늘 순서. */
data class KeywordTrend(
    val keyword: String,
    val daily: List<Int>,
    /** 가져온 기사 수가 한도(100건)에 닿아 오래된 날의 수가 실제보다 적을 수 있음. */
    val capped: Boolean,
    val error: String? = null
) {
    val today: Int get() = daily.lastOrNull() ?: 0
    val previousAverage: Double get() = daily.dropLast(1).average().let { if (it.isNaN()) 0.0 else it }
    val total: Int get() = daily.sum()

    /** 오늘 기사 수가 최소 5건이면서 지난 6일 평균의 2배 이상일 때 '급증'. */
    val isSurge: Boolean get() = today >= 5 && today >= 2 * maxOf(previousAverage, 1.0)

    companion object {
        const val DAYS = 7
        const val FETCH_LIMIT = 100

        fun compute(
            keyword: String,
            articles: List<NewsArticle>,
            today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
            zone: ZoneId = ZoneId.of("Asia/Seoul")
        ): KeywordTrend {
            val counts = IntArray(DAYS)
            for (article in articles) {
                if (article.publishedAt == Instant.EPOCH) continue
                val ago = java.time.temporal.ChronoUnit.DAYS.between(article.publishedAt.atZone(zone).toLocalDate(), today).toInt()
                if (ago in 0 until DAYS) counts[DAYS - 1 - ago]++
            }
            return KeywordTrend(keyword, counts.toList(), capped = articles.size >= FETCH_LIMIT)
        }
    }
}
