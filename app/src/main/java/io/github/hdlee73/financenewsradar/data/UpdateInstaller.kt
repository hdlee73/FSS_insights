package io.github.hdlee73.financenewsradar.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * 새 버전 APK를 **브라우저로** 내려받게 안내한다.
 *
 * 예전에는 앱이 직접 APK를 받아 설치 화면을 열었지만(REQUEST_INSTALL_PACKAGES 권한 필요),
 * "자기 자신을 내려받아 설치하는 앱"은 Play 프로텍트가 위험 신호로 보는 패턴이라 v2.7.1부터 권한을 뺐다.
 * 서명이 다른 APK는 안드로이드가 기존 앱 위 설치(업데이트)를 거부한다.
 */
object UpdateInstaller {
    /** 브라우저로 열 주소: APK 직접 링크, 없으면 릴리스 페이지. https가 아니면 쓰지 않는다. */
    fun downloadUrl(info: UpdateInfo): String? =
        listOfNotNull(info.apkUrl, info.url).map { it.trim() }.firstOrNull { it.startsWith("https://", ignoreCase = true) }

    /** 열었으면 true, 열 앱이 없거나 주소가 없으면 false. */
    fun openDownload(context: Context, info: UpdateInfo): Boolean {
        // 이전 버전이 앱 캐시에 받아 둔 APK가 남아 있으면 지운다.
        runCatching { File(context.cacheDir, "app_updates").deleteRecursively() }
        val url = downloadUrl(info) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
