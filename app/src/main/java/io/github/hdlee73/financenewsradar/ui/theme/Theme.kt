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

/** 화면별 머리말 색(밝은 테마 시작·끝, 어두운 테마 시작·끝). 화면마다 다른 색을 줘 한눈에 구분되게 한다. */
class HeroPalette(val start: Color, val end: Color, val darkStart: Color, val darkEnd: Color)

object HeroPalettes {
    val Sky = HeroPalette(Color(0xFF4FB4FB), Color(0xFF2563EB), Color(0xFF24457A), Color(0xFF1A2F63))
    val Ocean = HeroPalette(Color(0xFF38BDF8), Color(0xFF0E7490), Color(0xFF1F4A66), Color(0xFF134A5A))
    val Teal = HeroPalette(Color(0xFF3EDAC0), Color(0xFF2F7BEA), Color(0xFF1F5A55), Color(0xFF1D3F6E))
    val Green = HeroPalette(Color(0xFF5FD38D), Color(0xFF0F8F6A), Color(0xFF28603F), Color(0xFF14513F))
    val Indigo = HeroPalette(Color(0xFF7C9DFF), Color(0xFF4338CA), Color(0xFF34407A), Color(0xFF2B2B73))
    val Violet = HeroPalette(Color(0xFFB08CFF), Color(0xFF6D3FD6), Color(0xFF47367A), Color(0xFF3A2670))
    val Coral = HeroPalette(Color(0xFFFF9A6B), Color(0xFFE2503C), Color(0xFF7A4530), Color(0xFF6E2A22))
    val Amber = HeroPalette(Color(0xFFFFC857), Color(0xFFE07B1F), Color(0xFF6E5420), Color(0xFF6A3E14))
    val Slate = HeroPalette(Color(0xFF8FA6C4), Color(0xFF475A7A), Color(0xFF3A465A), Color(0xFF2B3446))
    val Rose = HeroPalette(Color(0xFFFF8DB0), Color(0xFFC2336B), Color(0xFF7A3550), Color(0xFF6A2146))
}

/** 지금 보이는 화면의 머리말 색. 위 메뉴 바와 각 화면의 머리말이 함께 따른다. */
val LocalHeroPalette = androidx.compose.runtime.compositionLocalOf { HeroPalettes.Sky }

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
    /** 화면 머리말(히어로) 그라데이션: 하늘색 → 파랑. 위 메뉴 바는 시작색과 같다. */
    val heroStart: Color @Composable get() = LocalHeroPalette.current.let { if (isSystemInDarkTheme()) it.darkStart else it.start }
    val heroEnd: Color @Composable get() = LocalHeroPalette.current.let { if (isSystemInDarkTheme()) it.darkEnd else it.end }
    val onHero: Color = Color.White
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
