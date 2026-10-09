package io.github.hdlee73.financenewsradar.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.hdlee73.financenewsradar.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 새 버전 APK를 GitHub 릴리스에서 내려받아 설치 화면을 띄운다. 최종 설치 확인은 안드로이드가 직접 묻는다. */
object UpdateInstaller {
    private const val DIR = "app_updates"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** '출처를 알 수 없는 앱 설치' 허용 화면(이 앱 전용)을 연다. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** APK를 내려받아 캐시에 저장한다. [onProgress]는 0..1(크기를 모르면 -1). 실패하면 예외. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: error("릴리스에 APK 파일이 없습니다.")
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "fss-insights-v${info.version}.apk")
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "FSSInsights/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) error("내려받기 실패 (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            if (total > 0 && done != total) error("내려받은 파일 크기가 맞지 않습니다.")
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            c.disconnect()
        }
        out
    }

    /** 내려받은 APK의 설치 화면을 연다. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
