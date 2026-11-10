package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NewsArticle
import java.time.Instant
import java.time.ZoneId

/** 오늘의 브리핑 '최신 주요 이슈' 카드의 규칙 기반 집계(웹 digest.js와 같은 규칙). */
data class IssueWord(val text: String, val count: Int)
data class LeaderSchedule(val who: String, val title: String, val link: String, val source: String, val at: Instant)
data class IssueDigest(val label: String, val total: Int, val words: List<IssueWord>, val schedules: List<LeaderSchedule>, val lead: List<NewsArticle>)

object IssueDigestBuilder {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val stop = setOf(
        "관련", "대한", "위한", "통해", "이번", "지난", "올해", "내년", "오늘", "내일", "기자", "단독", "속보", "종합", "포토", "영상", "사진",
        "한다", "했다", "하는", "된다", "있다", "없다", "것으로", "따르면", "에서", "으로", "에게", "대해", "이후", "가운데", "발표", "공개",
        "전망", "예정", "논란", "확대", "추진", "강화", "지원", "시작", "개최", "나선", "나온", "나서", "열린", "밝혔다", "금융", "은행", "증권", "보험", "시장", "정부", "한국"
    )
    private val josa = Regex("(으로|에서|에게|까지|부터|처럼|보다|이라|라고|이다|에는|에도|은|는|이|가|을|를|의|에|도|로|와|과|만)$")
    private val punct = Regex("[\\[\\]【】()「」'\"‘’“”·…,.:;!?]")
    private val edge = Regex("^[^가-힣a-zA-Z0-9]+|[^가-힣a-zA-Z0-9]+$")
    private val who = listOf(
        Regex("금융감독원장|금감원장") to "금융감독원장",
        Regex("금융위원장|금융위\\s*위원장") to "금융위원장"
    )
    private val sched = Regex("간담회|방문|참석|주재|면담|회동|회의|만나|현장|취임|출범|토론|특강|국정감사|국감|업무보고|기자간담|세미나|포럼|행사|점검|발표|개최|예정|오전|오후|내일|이날")

    fun titleTerms(title: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in punct.replace(title, " ").split(Regex("\\s+"))) {
            var w = raw.replace(edge, "")
            if (w.any { it in '가'..'힣' } && w.length > 2) w = w.replace(josa, "")
            if (w.length < 2 || w in stop || w.all { it.isDigit() }) continue
            out += w.lowercase()
        }
        return out
    }

    /** 금융감독원장·금융위원장 일정으로 보이는 기사(최근 순, 최대 4건). 제목 중복은 뺀다. */
    fun schedules(articles: List<NewsArticle>): List<LeaderSchedule> {
        val seen = HashSet<String>()
        return articles.mapNotNull { a ->
            val label = who.firstOrNull { it.first.containsMatchIn(a.title) }?.second ?: return@mapNotNull null
            if (!sched.containsMatchIn((a.title + " " + a.summary).take(200))) return@mapNotNull null
            val key = label + a.title.filter { it.isLetterOrDigit() }.take(14)
            if (!seen.add(key)) return@mapNotNull null
            LeaderSchedule(label, a.title, a.link, a.source, a.publishedAt)
        }.sortedByDescending { it.at }.take(4)
    }

    fun build(articles: List<NewsArticle>, leaderArticles: List<NewsArticle>, now: Instant = Instant.now()): IssueDigest? {
        val dayOf = { t: Instant -> t.atZone(seoul).toLocalDate() }
        val today = dayOf(now)
        var arts = articles.filter { dayOf(it.publishedAt) == today }
        var label = "오늘"
        if (arts.size < 3) {
            arts = articles.filter { java.time.Duration.between(it.publishedAt, now).toHours() < 24 }
            label = "최근 24시간"
        }
        if (arts.size < 3) {
            val latest = articles.maxOfOrNull { dayOf(it.publishedAt) }
            if (latest != null) {
                arts = articles.filter { dayOf(it.publishedAt) == latest }
                label = if (latest == today) "오늘" else "${latest.monthValue}월 ${latest.dayOfMonth}일"
            }
        }
        if (arts.isEmpty()) return null
        val known = arts.flatMap { it.matchedKeywords }.flatMap { it.lowercase().split(Regex("\\s+")) }.toSet()
        val freq = HashMap<String, Int>()
        for (a in arts) for (t in titleTerms(a.title)) if (t !in known) freq[t] = (freq[t] ?: 0) + 1
        for (a in arts) for (k in a.matchedKeywords) freq[k.lowercase()] = (freq[k.lowercase()] ?: 0) + 1
        val words = freq.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .filter { it.value >= 2 }.take(8).map { IssueWord(it.key, it.value) }
        val lead = arts.filter { PublisherCatalog.isMajor(it.source, it.link) }.sortedByDescending { it.publishedAt }.take(6)
        return IssueDigest(label, arts.size, words, schedules(leaderArticles + arts), lead)
    }
}
