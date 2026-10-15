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
 * 연한 하늘색 바탕 · 흰 카드 · 둥근 모서리의 밝은 디자인. 강조색은 밝은 파랑, 새 자료 표시에는 주황을 쓴다.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A7DE1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFC),
    onPrimaryContainer = Color(0xFF1A5FB4),
    secondary = Color(0xFF47638A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF1F5),
    onSecondaryContainer = Color(0xFF232A36),
    tertiary = Color(0xFFF08A4B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCEDE5),
    onTertiaryContainer = Color(0xFFA8461A),
    background = Color(0xFFF2F7FC),
    onBackground = Color(0xFF1D2B3F),
    surface = Color(0xFFF2F7FC),
    onSurface = Color(0xFF1D2B3F),
    surfaceVariant = Color(0xFFE6EEF8),
    onSurfaceVariant = Color(0xFF64778F),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEAF2FB),
    outline = Color(0xFFA3AAB5),
    outlineVariant = Color(0xFFE3ECF6),
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

/** 상단 바·강조에 쓰는 색(연한 하늘색 헤더 + 파랑 포인트 + 주황 보조색). */
object AppColors {
    val headerLight = Color(0xFFE8F2FD)
    val headerDark = Color(0xFF151A23)
    /** 머리말 그라데이션 끝색(밝은 하늘색 → 바탕색). */
    val headerEndLight = Color(0xFFCFE4FB)
    val headerEndDark = Color(0xFF1F2A40)
    val onHeaderLight = Color(0xFF12305A)
    val onHeaderDark = Color.White
    val accentLight = Color(0xFFE2622B)
    val accentDark = Color(0xFFFF9A72)
    val header: Color @Composable get() = if (isSystemInDarkTheme()) headerDark else headerLight
    val headerEnd: Color @Composable get() = if (isSystemInDarkTheme()) headerEndDark else headerEndLight
    /** 머리말 위 글자·아이콘 색(밝은 테마는 짙은 남색, 어두운 테마는 흰색). */
    val onHeader: Color @Composable get() = if (isSystemInDarkTheme()) onHeaderDark else onHeaderLight
    val accent: Color @Composable get() = if (isSystemInDarkTheme()) accentDark else accentLight
}

@Composable
fun FinanceNewsRadarTheme(content: @Composable () -> Unit) {
    val colors: ColorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
