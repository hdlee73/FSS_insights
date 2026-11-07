package io.github.hdlee73.financenewsradar.ui

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.hdlee73.financenewsradar.data.FundAumCompany
import io.github.hdlee73.financenewsradar.data.FundAumDataset
import io.github.hdlee73.financenewsradar.data.FundAumDates
import io.github.hdlee73.financenewsradar.ui.theme.FinanceNewsRadarTheme
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 화면 캡처용(PR 확인 목적, 병합하지 않음). 실제 수집 자료의 숫자로 펀드 순자산 탭 본문을 그려 PNG를 로그로 내보낸다. */
class FundAumShotTest {
    @get:Rule val rule = createComposeRule()

    private val data = FundAumDataset("2026-11-07T19:30:08+09:00", FundAumDates("20261105", "20260930", "20251231"), emptyList())

    private fun shot(name: String, c: FundAumCompany) {
        rule.setContent {
            FinanceNewsRadarTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Text(c.name, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                    FundAumBody(c, data)
                }
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        b64.chunked(900).forEachIndexed { i, part -> Log.i("FUNDSHOT", "$name|$i|$part") }
        Log.i("FUNDSHOT", "$name|END|${b64.length}")
    }

    @Test fun kyobo() = shot("kyobo", FundAumCompany("교보자산운용", listOf(105356, 111726, 116710), listOf(137862, 137021, 141510)))
    @Test fun mirae() = shot("mirae", FundAumCompany("미래에셋자산운용", listOf(1678481, 1850141, 1321777), listOf(985993, 932511, 795978)))
    @Test fun koreaInvest() = shot("koreainvest", FundAumCompany("한국투자증권", listOf(0, 0, 0), listOf(10629, 10394, 37400)))
}
