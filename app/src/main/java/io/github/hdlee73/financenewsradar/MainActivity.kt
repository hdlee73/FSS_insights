package io.github.hdlee73.financenewsradar

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.hdlee73.financenewsradar.data.UpdateChecker
import io.github.hdlee73.financenewsradar.ui.FinanceNewsRadarApp
import io.github.hdlee73.financenewsradar.ui.theme.FinanceNewsRadarTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 허용 직후 바로 알림을 띄우기 위해 한 번 더 확인한다.
            if (granted) checkForUpdate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinanceNewsRadarTheme { FinanceNewsRadarApp() }
        }
        if (savedInstanceState == null) {
            checkForUpdate()
            if (UpdateChecker.needsNotificationPermission(this)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch { UpdateChecker.check(applicationContext) }
    }
}
