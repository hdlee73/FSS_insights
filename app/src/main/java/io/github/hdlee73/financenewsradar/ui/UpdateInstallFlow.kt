package io.github.hdlee73.financenewsradar.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hdlee73.financenewsradar.data.UpdateInfo
import io.github.hdlee73.financenewsradar.data.UpdateInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 새 버전 APK를 내려받아 설치 화면을 띄우는 흐름. 앱 정보 화면과 시작 팝업이 같이 쓴다. */
class UpdateInstallFlow(private val context: Context, private val scope: CoroutineScope) {
    /** 0..1, 크기를 모르면 -1, 내려받는 중이 아니면 null. */
    var progress by mutableStateOf<Float?>(null)
    var message by mutableStateOf<String?>(null)
    internal var downloaded: File? = null
    val busy: Boolean get() = progress != null

    /** 내려받아 설치한다. 이 앱의 '출처를 알 수 없는 앱 설치'가 막혀 있으면 APK를 받아 둔 뒤 허용 화면을 연다. */
    fun start(info: UpdateInfo) {
        if (busy) return
        scope.launch {
            message = null
            progress = 0f
            try {
                val apk = UpdateInstaller.download(context, info) { progress = it }
                downloaded = apk
                progress = null
                if (UpdateInstaller.canInstall(context)) {
                    UpdateInstaller.install(context, apk)
                } else {
                    message = "이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 돌아오면 이어서 설치합니다."
                    UpdateInstaller.openInstallPermissionSettings(context)
                }
            } catch (e: Exception) {
                progress = null
                message = e.message ?: "내려받기에 실패했습니다."
            }
        }
    }

    internal fun resumeInstallIfAllowed() {
        val apk = downloaded ?: return
        if (!UpdateInstaller.canInstall(context)) return
        downloaded = null
        if (apk.exists()) runCatching { UpdateInstaller.install(context, apk) }
    }
}

@Composable
fun rememberUpdateInstallFlow(): UpdateInstallFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { UpdateInstallFlow(context, scope) }
    // 허용하고 돌아오면 받아 둔 APK 설치를 이어 간다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) flow.resumeInstallIfAllowed() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return flow
}
