package io.github.hdlee73.financenewsradar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2E5),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFFB44920),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF3C0B00),
    background = Color(0xFFF3F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4EDF2),
    outline = Color(0xFF6F7978),
    outlineVariant = Color(0xFFC1CAC8)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF88D8CD),
    primaryContainer = Color(0xFF00504C),
    secondary = Color(0xFFFFB59C),
    secondaryContainer = Color(0xFF7F2A08),
    background = Color(0xFF0D1417),
    surface = Color(0xFF151D20),
    surfaceVariant = Color(0xFF3E494B),
    outlineVariant = Color(0xFF3F4948)
)

@Composable
fun FinanceNewsRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
