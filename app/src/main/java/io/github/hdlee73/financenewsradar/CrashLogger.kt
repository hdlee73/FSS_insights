package io.github.hdlee73.financenewsradar

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 마켓 없이 배포하므로, 치명적 오류가 나면 원인을 기기 안 로그 파일에 남긴다(최근 10개만 보관). */
object CrashLogger {
    private const val KEEP = 10
    private fun dir(context: Context) = File(context.filesDir, "logs")

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "치명적 오류 (스레드: ${thread.name})", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 잡아서 처리한 예외도 원인 추적이 필요하면 남긴다. */
    fun log(context: Context, label: String, error: Throwable) {
        runCatching { write(context.applicationContext, label, error) }
    }

    private fun write(context: Context, label: String, error: Throwable) {
        val d = dir(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        File(d, "crash-$stamp.txt").writeText(
            "$label\n시각: $stamp\n앱 버전: ${BuildConfig.VERSION_NAME}\n" +
                "기기: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n\n$sw"
        )
        d.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** 가장 최근 로그 본문(없으면 null) */
    fun latest(context: Context): String? =
        dir(context).listFiles()?.maxByOrNull { it.name }?.let { runCatching { it.readText() }.getOrNull() }
}
