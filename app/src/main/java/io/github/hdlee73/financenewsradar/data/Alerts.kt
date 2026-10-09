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
import io.github.hdlee73.financenewsradar.model.AgencyGroup
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.TimeRange
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 앱이 보내는 알림의 공통 표시 부분(채널·권한 확인·앱 열기 동작). */
internal object AlertNotifier {
    private const val MAX_LINES = 5

    fun post(context: Context, channelId: String, channelName: String, id: Int, title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val style = NotificationCompat.InboxStyle().also { box ->
            lines.take(MAX_LINES).forEach(box::addLine)
            if (lines.size > MAX_LINES) box.setSummaryText("외 ${lines.size - MAX_LINES}건")
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}

/** 보도자료·연구자료가 올라오면 알리는 기능. 기관별로 켜고 끈다. */
object NewMaterialAlerts {
    private const val CHANNEL_ID = "material_alerts"

    /** 알림을 고를 수 있는 기관(직접 추가한 연구소는 [AgencyId.CUSTOM] 하나로 묶는다). */
    val sources: List<AgencyId> = AgencyId.entries

    fun anyEnabled(store: SettingsStore): Boolean = sources.any(store::materialAlertEnabled)

    /** 켜진 기관이 하나라도 있으면 1시간마다 확인한다(키워드 알림의 1시간 작업이 함께 처리). */
    internal suspend fun check(context: Context) {
        val store = SettingsStore(context)
        if (UpdateChecker.needsNotificationPermission(context)) return
        val enabled = sources.filter(store::materialAlertEnabled)
        if (enabled.isEmpty()) return
        val agencies = AgencyRepository(context)
        for (group in AgencyGroup.entries) {
            val fresh = mutableListOf<AlertHit>()
            for (agency in enabled.filter { it.group == group }) {
                if (agency == AgencyId.CUSTOM) {
                    for (institute in store.loadInstitutes()) {
                        val hits = runCatching { agencies.latestCustom(institute, 20) }.getOrNull()
                            ?.map { AlertHit(it.title, it.link, institute.name) }
                        fresh += diffAndRecord(store, "material_custom_${institute.url}", hits)
                    }
                } else {
                    val hits = runCatching { agencies.latest(agency, 20) }.getOrNull()
                        ?.map { AlertHit(it.title, it.link, agency.shortLabel) }
                    fresh += diffAndRecord(store, "material_${agency.name}", hits)
                }
            }
            if (fresh.isNotEmpty()) {
                val noun = if (group == AgencyGroup.PRESS) "보도자료" else "연구자료"
                AlertNotifier.post(
                    context, CHANNEL_ID, "새 자료 알림", 3001 + group.ordinal,
                    "새 $noun ${fresh.size}건", fresh.map { "[${it.source}] ${it.title}" }
                )
            }
        }
    }

    /** 처음 보는 기관이면 지금 목록을 '이미 본 것'으로만 기록(알리지 않음). */
    private fun diffAndRecord(store: SettingsStore, key: String, hits: List<AlertHit>?): List<AlertHit> {
        val (fresh, seen) = KeywordAlertLogic.diff(hits, store.seenLinks(key))
        if (seen != null) store.saveSeenLinks(key, seen)
        return fresh
    }
}

/** 매일 정한 시각에 오늘의 브리핑(시장·주요 기사·최근 보도자료)을 요약해 알린다. */
object BriefingAlert {
    private const val WORK_NAME = "briefing_alert"
    private const val CHANNEL_ID = "briefing_alert"
    private val SEOUL = ZoneId.of("Asia/Seoul")
    private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

    /** 지금부터 다음 [hour]:[minute](서울 시각)까지 기다릴 시간. 이미 지났으면 내일. */
    internal fun initialDelay(now: ZonedDateTime, hour: Int, minute: Int): Duration {
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }

    fun formatTime(minutesOfDay: Int): String = "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)

    /**
     * 켜져 있으면 다음 알림 시각까지 기다렸다 하루 한 번 도는 주기 작업을 등록하고, 꺼져 있으면 취소한다.
     * 정확한 알람 권한 없이 WorkManager로 예약하므로 정각이 아니라 몇 분 안팎 늦어질 수 있다(절전 모드에서는 더 늦을 수 있음).
     */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        val store = SettingsStore(app)
        if (!store.briefingAlertEnabled()) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val minutes = store.briefingAlertMinutes()
        val delay = initialDelay(ZonedDateTime.now(SEOUL), minutes / 60, minutes % 60)
        val request = PeriodicWorkRequestBuilder<BriefingAlertWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // 시각을 바꾼 경우에도 새 시각부터 다시 세도록 기존 예약을 지우고 다시 등록한다.
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    /** 앱을 열 때: 예약이 살아 있게만 한다(이미 있으면 그대로 두어 시각이 밀리지 않게). */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val store = SettingsStore(app)
        if (!store.briefingAlertEnabled()) return
        val infos = runCatching { WorkManager.getInstance(app).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull()
        if (infos == null || infos.none { !it.state.isFinished }) reschedule(app)
    }

    internal suspend fun check(context: Context) {
        val store = SettingsStore(context)
        if (!store.briefingAlertEnabled() || UpdateChecker.needsNotificationPermission(context)) return
        val today = LocalDate.now(SEOUL)
        val lines = mutableListOf<String>()

        runCatching {
            val market = MarketClient(context)
            val parts = listOf("^KS11" to "코스피", "^KQ11" to "코스닥").mapNotNull { (symbol, name) ->
                runCatching { market.quote(symbol) }.getOrNull()?.let { q ->
                    val pct = q.changePercent
                    if (pct == null) "$name ${"%,.2f".format(q.price)}"
                    else "$name ${"%,.2f".format(q.price)} (${if (pct >= 0) "▲" else "▼"}${"%.2f".format(kotlin.math.abs(pct))}%)"
                }
            }
            if (parts.isNotEmpty()) lines += parts.joinToString(" · ")
        }

        runCatching {
            val settings = store.loadSettings().copy(timeRange = TimeRange.DAY)
            val articles = NewsRepository().home(settings, store.loadCredentials()).articles
                .filter { it.matchedKeywords.isNotEmpty() }
                .sortedByDescending { it.publishedAt }
                .take(3)
            articles.forEach { lines += "[기사] ${it.title}" }
        }

        runCatching {
            val repo = AgencyRepository(context)
            val recent = listOf(AgencyId.FSS, AgencyId.FSC).flatMap { agency ->
                runCatching { repo.latest(agency, 10) }.getOrDefault(emptyList())
            }.filter { item -> item.date?.let { !it.isBefore(today.minusDays(1)) } == true }
                .sortedByDescending { it.date }
                .take(2)
            recent.forEach { lines += "[${it.agency.shortLabel}] ${it.title}" }
        }

        if (lines.isEmpty()) return
        AlertNotifier.post(context, CHANNEL_ID, "오늘의 브리핑", 3000, "오늘의 브리핑 · ${today.format(dateFormat)}", lines)
    }
}

class BriefingAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { BriefingAlert.check(applicationContext) }
        return Result.success()
    }
}
