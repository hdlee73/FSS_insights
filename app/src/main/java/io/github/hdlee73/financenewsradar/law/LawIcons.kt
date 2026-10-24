package io.github.hdlee73.financenewsradar.law

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 네이비 프로 시안의 메뉴 아이콘: 둥근 1.75 선 + 18% 채움(듀오톤).
 * 색은 Icon의 tint가 입히므로 여기서는 검정으로 그린다.
 */
object EverylawIcons {
    private fun duotone(name: String, fill: String, vararg lines: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(fill), fill = SolidColor(Color.Black), fillAlpha = 0.18f)
            addPath(
                addPathNodes(fill), stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            lines.forEach {
                addPath(
                    addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Search: ImageVector by lazy { duotone("search", "M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0", "M20 20l-3.5-3.5") }
    val Folder: ImageVector by lazy { duotone("folder", "M4 6a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z") }
    val Clock: ImageVector by lazy { duotone("clock", "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0", "M12 7.5V12l3 2") }
    /** 로고에 쓰는 계단선(앱 아이콘의 5단 계단을 한 줄로 줄인 것) */
    val Stairs: ImageVector by lazy {
        ImageVector.Builder("stairs", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                addPathNodes("M5 19h3v-3h3v-3h3v-3h3V7"), stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
    val Gear: ImageVector by lazy { duotone("gear", "M12 2.8l7.8 4.5v9.4L12 21.2l-7.8-4.5V7.3z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0") }
}
