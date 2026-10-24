package io.github.hdlee73.financenewsradar.law

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.hdlee73.financenewsradar.ui.theme.AppColors

// 법령 화면은 앱 공통 테마(머리말 색)를 따른다. 머리띠 색은 현재 화면 팔레트의 끝색, 강조는 흰색.
val Navy: Color @Composable get() = AppColors.heroEnd
val Gold: Color get() = Color.White

val LawBodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 24.sp)

/** 계층 하나의 톤: 카드 배경(container), 카드 글자(onContainer), 배지·단계 번호(accent/onAccent) */
@Immutable
data class LevelTone(val container: Color, val onContainer: Color, val accent: Color, val onAccent: Color)

@Immutable
class LevelPalette(private val tones: Map<com.hdlee73.everylaw.core.model.LawLevel, LevelTone>) {
    operator fun get(level: com.hdlee73.everylaw.core.model.LawLevel): LevelTone = tones.getValue(level)
}

private val LinkBlue = Color(0xFF2F5BD3)
private val DeepNavy = Color(0xFF0B1B3A)
private val GoldAccent = Color(0xFFC8A24A)

private val LightLevels = LevelPalette(
    mapOf(
        com.hdlee73.everylaw.core.model.LawLevel.LAW to LevelTone(Color(0xFFE7ECF8), DeepNavy, DeepNavy, Color.White),
        com.hdlee73.everylaw.core.model.LawLevel.DECREE to LevelTone(Color(0xFFE7ECF8), DeepNavy, LinkBlue, Color.White),
        com.hdlee73.everylaw.core.model.LawLevel.RULE to LevelTone(Color(0xFFE3F4F0), Color(0xFF0B3D35), Color(0xFF0F7B6C), Color.White),
        com.hdlee73.everylaw.core.model.LawLevel.SUPERVISORY_REGULATION to LevelTone(Color(0xFFF6EEDB), Color(0xFF3F3010), GoldAccent, DeepNavy),
        com.hdlee73.everylaw.core.model.LawLevel.ENFORCEMENT_DETAIL to LevelTone(Color(0xFFE3E8F1), DeepNavy, Color(0xFF56627A), Color.White),
    )
)

private val DarkLevels = LevelPalette(
    mapOf(
        com.hdlee73.everylaw.core.model.LawLevel.LAW to LevelTone(Color(0xFF1C2A4B), Color(0xFFE3E8F5), Color(0xFFE3E8F5), DeepNavy),
        com.hdlee73.everylaw.core.model.LawLevel.DECREE to LevelTone(Color(0xFF1C2A4B), Color(0xFFE3E8F5), Color(0xFF9DB6FF), Color(0xFF0A1F5C)),
        com.hdlee73.everylaw.core.model.LawLevel.RULE to LevelTone(Color(0xFF123A33), Color(0xFFD3F1EA), Color(0xFF6FD0BF), Color(0xFF00382F)),
        com.hdlee73.everylaw.core.model.LawLevel.SUPERVISORY_REGULATION to LevelTone(Color(0xFF3A3220), Color(0xFFF6EEDB), GoldAccent, DeepNavy),
        com.hdlee73.everylaw.core.model.LawLevel.ENFORCEMENT_DETAIL to LevelTone(Color(0xFF243350), Color(0xFFD5DCEC), Color(0xFFA8B3CC), DeepNavy),
    )
)

object LevelColors {
    val current: LevelPalette @Composable get() = if (isSystemInDarkTheme()) DarkLevels else LightLevels
}
