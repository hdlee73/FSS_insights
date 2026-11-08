package io.github.hdlee73.financenewsradar

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.hdlee73.financenewsradar.data.BriefingAlert
import io.github.hdlee73.financenewsradar.data.KeywordAlerts
import io.github.hdlee73.financenewsradar.data.UpdateChecker
import io.github.hdlee73.financenewsradar.data.UsageStats
import io.github.hdlee73.financenewsradar.ui.FinanceNewsRadarApp
import io.github.hdlee73.financenewsradar.ui.theme.FinanceNewsRadarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 허용 직후 바로 알림을 띄우기 위해 한 번 더 확인한다.
            if (granted) checkForUpdate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        enableEdgeToEdge()
        setContent {
            FinanceNewsRadarTheme { FinanceNewsRadarApp() }
        }
        if (savedInstanceState == null) {
            checkForUpdate()
            restoreAlertSchedules()
            UpdateChecker.scheduleBackgroundCheck(this)
            if (UpdateChecker.needsNotificationPermission(this)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 익명 사용 통계: 하루 한 번만 실제로 전송한다(꺼져 있거나 서버가 없으면 아무 일도 하지 않는다).
        lifecycleScope.launch { UsageStats.sendIfNeeded(applicationContext) }
    }

    /** 예약이 빠졌거나 주기가 바뀐 경우를 위해 앱을 열 때 알림 예약을 설정에 맞춰 다시 확인한다. */
    private fun restoreAlertSchedules() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                KeywordAlerts.reschedule(applicationContext)
                BriefingAlert.ensureScheduled(applicationContext)
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch { UpdateChecker.check(applicationContext) }
    }
}
