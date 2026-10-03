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
    primary = Color(0xFF14418C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EDF9),
    onPrimaryContainer = Color(0xFF0B2A5C),
    secondary = Color(0xFF5B6575),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF0F3),
    onSecondaryContainer = Color(0xFF1F2530),
    tertiary = Color(0xFFF2672E),
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111318),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F3F5),
    outline = Color(0xFF9AA1AD),
    outlineVariant = Color(0xFFE6E8EB),
    error = Color(0xFFD92D20)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB2FF),
    onPrimary = Color(0xFF0B1E44),
    primaryContainer = Color(0xFF1E2B45),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFA7B0BE),
    secondaryContainer = Color(0xFF262B33),
    onSecondaryContainer = Color(0xFFE6E9EE),
    tertiary = Color(0xFFFF8A5C),
    onTertiary = Color(0xFF3A1200),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFF2F4F7),
    surface = Color(0xFF0B0D10),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1B1F25),
    onSurfaceVariant = Color(0xFF9AA3AF),
    surfaceContainer = Color(0xFF12151A),
    surfaceContainerHigh = Color(0xFF1B1F25),
    outline = Color(0xFF6B7380),
    outlineVariant = Color(0xFF262B33),
    error = Color(0xFFFF6B5E)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
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

@Composable
fun FinanceNewsRadarTheme(content: @Composable () -> Unit) {
    val colors: ColorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
