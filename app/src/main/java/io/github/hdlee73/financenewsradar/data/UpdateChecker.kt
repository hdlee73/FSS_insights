package io.github.hdlee73.financenewsradar.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.hdlee73.financenewsradar.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** GitHub 최신 릴리스 정보. */
data class UpdateInfo(val version: String, val url: String)

/** 마지막 확인 결과. 앱 정보 화면이 구독한다. */
object UpdateStatus {
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available
    internal fun set(info: UpdateInfo?) { _available.value = info }
}

object UpdateChecker {
    const val RELEASES_URL = "https://github.com/hdlee73/FSS_insights/releases"
    private const val LATEST_API = "https://api.github.com/repos/hdlee73/FSS_insights/releases/latest"
    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 1001
    private const val PREFS = "update_checker"
    private const val KEY_NOTIFIED = "notified_version"

    /** "v0.12.1" / "0.12.1-debug" 같은 문자열을 숫자 목록으로. 해석 불가면 null. */
    internal fun parseVersion(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** latest가 current보다 높으면 true. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = URI(LATEST_API).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "FSSInsights/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) return@withContext null
            val json = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return@withContext null
            UpdateInfo(tag.removePrefix("v"), json.optString("html_url").ifBlank { RELEASES_URL })
        } finally {
            c.disconnect()
        }
    }

    /** 앱 시작 시 한 번 호출. 실패(오프라인 등)는 조용히 무시한다. */
    suspend fun check(context: Context) {
        val latest = runCatching { fetchLatest() }.getOrNull() ?: return
        if (!isNewer(latest.version, BuildConfig.VERSION_NAME)) {
            UpdateStatus.set(null)
            return
        }
        UpdateStatus.set(latest)
        notifyOnce(context.applicationContext, latest)
    }

    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** 같은 버전에는 한 번만 알린다. 권한이 없으면 기록하지 않아 허용 후 다음 실행에서 다시 시도한다. */
    private fun notifyOnce(context: Context, info: UpdateInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED, null) == info.version) return
        if (needsNotificationPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(info.url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("FSS Insights 새 버전 ${info.version}")
            .setContentText("GitHub 릴리스에서 업데이트를 받을 수 있습니다.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_NOTIFIED, info.version).apply()
    }
}
