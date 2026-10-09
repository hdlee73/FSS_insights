package io.github.hdlee73.financenewsradar.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.hdlee73.financenewsradar.MainActivity
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.TimeRange
import java.util.concurrent.TimeUnit

/** 알릴 항목 하나(기사 또는 보도자료). [link]가 같은 항목은 같은 키워드로 두 번 알리지 않는다. */
data class AlertHit(val title: String, val link: String, val source: String)

/** 키워드 알림의 판단 로직(네트워크·알림과 분리해 테스트한다). */
object KeywordAlertLogic {
    /**
     * [seen]이 null이면 이 키워드를 처음 보는 것이므로 지금 있는 항목을 '이미 본 것'으로만 기록하고 알리지 않는다
     * (키워드를 추가하거나 알림을 켠 순간 묵은 기사가 한꺼번에 울리지 않게).
     * 반환: (알릴 항목, 저장할 seen 목록). 확인에 실패해 [hits]가 null이면 아무것도 바꾸지 않는다.
     */
    fun diff(hits: List<AlertHit>?, seen: List<String>?): Pair<List<AlertHit>, List<String>?> {
        if (hits == null) return emptyList<AlertHit>() to seen
        val distinct = hits.distinctBy { it.link }
        if (seen == null) return emptyList<AlertHit>() to distinct.map { it.link }
        val known = seen.toHashSet()
        val fresh = distinct.filter { it.link !in known }
        return fresh to (seen + fresh.map { it.link })
    }

    /** 보도자료 제목이 키워드(OR 조합 포함)에 해당하고 제외어가 없는지. */
    fun titleMatches(title: String, keyword: String): Boolean {
        val plan = runCatching { SearchQueryParser.parse(keyword) }.getOrNull() ?: return false
        if (SearchQueryParser.isExcluded(title, plan.excludedTerms)) return false
        return plan.terms.any { title.contains(it, ignoreCase = true) }
    }
}

object KeywordAlerts {
    private const val WORK_NAME = "keyword_alerts"
    private const val CHANNEL_ID = "keyword_alerts"
    private const val REPEAT_HOURS = 3L
    private const val MAX_LINES = 5
    private val PRESS_AGENCIES = listOf(AgencyId.FSS, AgencyId.FSC)

    fun needsNotificationPermission(context: Context): Boolean = UpdateChecker.needsNotificationPermission(context)

    /** 켜면 주기 확인을 등록하고, 끄면 취소한다. 이미 등록돼 있으면 그대로 둔다. */
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<KeywordAlertWorker>(REPEAT_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 키워드마다 새 기사·보도자료를 확인해 알린다. 알림 권한이 없으면 기록도 하지 않아 허용 후 이어서 알린다. */
    internal suspend fun check(context: Context) {
        val store = SettingsStore(context)
        if (!store.keywordAlertsEnabled() || needsNotificationPermission(context)) return
        val settings = store.loadSettings()
        val credentials = store.loadCredentials()
        val news = NewsRepository()
        val agencies = AgencyRepository(context)
        val releases = PRESS_AGENCIES.associateWith { agency ->
            runCatching { agencies.latest(agency, 20) }.getOrNull()
        }
        for (keyword in settings.keywords) {
            val newsHits = runCatching {
                news.search(keyword, settings.copy(timeRange = TimeRange.DAY), credentials).articles
                    .map { AlertHit(it.title, it.link, it.source) }
            }.getOrNull()
            val pressHits = releases.mapNotNull { (agency, items) ->
                items?.filter { KeywordAlertLogic.titleMatches(it.title, keyword) }
                    ?.map { AlertHit(it.title, it.link, agency.shortLabel) }
            }.flatten()
            // 기사 조회가 실패하면 이번에는 건너뛰고(기록 없음), 다음 주기에 다시 본다.
            val hits = newsHits?.plus(pressHits)
            val key = "alert_$keyword"
            val (fresh, seen) = KeywordAlertLogic.diff(hits, store.seenLinks(key))
            if (seen != null) store.saveSeenLinks(key, seen)
            if (fresh.isNotEmpty()) notify(context, keyword, fresh)
        }
    }

    private fun notify(context: Context, keyword: String, hits: List<AlertHit>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= 33
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "키워드 알림", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lines = hits.take(MAX_LINES).map { "[${it.source}] ${it.title}" }
        val style = NotificationCompat.InboxStyle().also { box ->
            lines.forEach(box::addLine)
            if (hits.size > MAX_LINES) box.setSummaryText("외 ${hits.size - MAX_LINES}건")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("‘$keyword’ 새 소식 ${hits.size}건")
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(2000 + (keyword.hashCode() and 0xFFFF), notification)
    }
}

class KeywordAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { KeywordAlerts.check(applicationContext) }
        return Result.success()
    }
}
