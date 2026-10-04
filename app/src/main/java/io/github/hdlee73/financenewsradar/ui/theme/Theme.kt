package io.github.hdlee73.financenewsradar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 흰 바탕 · 얇은 구분선 · 큰 제목 · 밑줄 탭으로 이루어진 담백한 디자인(Apple 앱과 증권 앱의 목록 화면을 참고).
 * 강조색은 차분한 감독 네이비 하나만 쓰고, 새 자료 표시에만 주황을 쓴다.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B4C8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EEF8),
    onPrimaryContainer = Color(0xFF1B3263),
    secondary = Color(0xFF5E6878),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF1F5),
    onSecondaryContainer = Color(0xFF232A36),
    tertiary = Color(0xFFD9642F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCEDE5),
    onTertiaryContainer = Color(0xFFA8461A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14171C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFF0F1F4),
    onSurfaceVariant = Color(0xFF6C7482),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF3F4F7),
    outline = Color(0xFFA3AAB5),
    outlineVariant = Color(0xFFECEEF2),
    error = Color(0xFFC9372C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB5E6),
    onPrimary = Color(0xFF0E2048),
    primaryContainer = Color(0xFF1F2A40),
    onPrimaryContainer = Color(0xFFDCE6FA),
    secondary = Color(0xFFA9B2C0),
    secondaryContainer = Color(0xFF262B33),
    onSecondaryContainer = Color(0xFFE6E9EE),
    tertiary = Color(0xFFFF9A72),
    onTertiary = Color(0xFF3A1200),
    tertiaryContainer = Color(0xFF3A2218),
    onTertiaryContainer = Color(0xFFFFB59A),
    background = Color(0xFF0C0E11),
    onBackground = Color(0xFFF1F3F6),
    surface = Color(0xFF0C0E11),
    onSurface = Color(0xFFF1F3F6),
    surfaceVariant = Color(0xFF1A1E24),
    onSurfaceVariant = Color(0xFF9BA4B0),
    surfaceContainer = Color(0xFF12151A),
    surfaceContainerHigh = Color(0xFF1A1E24),
    outline = Color(0xFF6B7380),
    outlineVariant = Color(0xFF252A32),
    error = Color(0xFFFF7A6E)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = (-0.2).sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

/** 상단 바·강조에 쓰는 색(클리앙식 짙은 네이비 헤더 + 주황 포인트). */
object AppColors {
    val headerLight = Color(0xFF262F40)
    val headerDark = Color(0xFF151A23)
    val accentLight = Color(0xFFE2622B)
    val accentDark = Color(0xFFFF9A72)
    val header: Color @Composable get() = if (isSystemInDarkTheme()) headerDark else headerLight
    val accent: Color @Composable get() = if (isSystemInDarkTheme()) accentDark else accentLight
}

@Composable
fun FinanceNewsRadarTheme(content: @Composable () -> Unit) {
    val colors: ColorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
