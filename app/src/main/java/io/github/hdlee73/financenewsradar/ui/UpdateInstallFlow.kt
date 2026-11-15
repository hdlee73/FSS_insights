package io.github.hdlee73.financenewsradar.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.hdlee73.financenewsradar.data.UpdateInfo
import io.github.hdlee73.financenewsradar.data.UpdateInstaller

/** 새 버전 APK를 브라우저로 내려받게 여는 흐름. 앱 정보 화면과 시작 팝업이 같이 쓴다. */
class UpdateInstallFlow(private val context: Context) {
    /** 내려받기를 연 뒤 보여 줄 안내. */
    var notice by mutableStateOf<String?>(null)
    /** 실패 문구. */
    var message by mutableStateOf<String?>(null)
    /** 이제는 앱이 내려받지 않으므로 항상 null(화면 호환용). */
    val progress: Float? get() = null
    val busy: Boolean get() = false

    fun start(info: UpdateInfo) {
        if (UpdateInstaller.openDownload(context, info)) {
            message = null
            notice = "브라우저에서 내려받기가 시작됩니다. 다 받으면 알림(또는 '내 파일 > 다운로드')의 APK를 눌러 설치하세요."
        } else {
            notice = null
            message = "내려받기 주소를 열 수 없습니다. 브라우저에서 GitHub 릴리스 페이지를 직접 열어 주세요."
        }
    }
}

@Composable
fun rememberUpdateInstallFlow(): UpdateInstallFlow {
    val context = LocalContext.current.applicationContext
    return remember { UpdateInstallFlow(context) }
}
