package io.github.hdlee73.financenewsradar.law.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 분할 화면(좌우) 사용 방식 */
enum class SplitMode(val label: String, val desc: String) {
    Auto("자동", "화면이 넓을 때만 좌우로 나눠요"),
    Always("항상 분할", "화면 크기와 상관없이 좌우로 나눠요"),
    Never("분할 안 함", "항상 한 열(아코디언)로 보여요"),
}

data class AppSettings(
    val splitMode: SplitMode = SplitMode.Auto,
    val recent: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
)

/** 법령 화면의 최근 검색·보관함·분할 방식을 기기에만 저장한다(SharedPreferences). */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("law_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read() = AppSettings(
        splitMode = SplitMode.entries.firstOrNull { it.name == prefs.getString(KEY_SPLIT, null) } ?: SplitMode.Auto,
        recent = prefs.getString(KEY_RECENT, null).toList(),
        favorites = prefs.getString(KEY_FAVORITES, null).toList(),
    )

    private fun commit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = read()
    }

    fun setSplitMode(value: SplitMode) = commit { putString(KEY_SPLIT, value.name) }

    fun addRecent(query: String) = commit {
        putString(KEY_RECENT, (listOf(query) + _settings.value.recent.filter { it != query }).take(20).join())
    }

    fun clearRecent() = commit { remove(KEY_RECENT) }

    fun toggleFavorite(query: String) = commit {
        val list = _settings.value.favorites
        putString(KEY_FAVORITES, (if (query in list) list - query else listOf(query) + list).join())
    }

    private fun String?.toList(): List<String> = this?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
    private fun List<String>.join(): String = joinToString(SEP)

    private companion object {
        const val SEP = "\u001F"
        const val KEY_SPLIT = "split_mode"
        const val KEY_RECENT = "recent"
        const val KEY_FAVORITES = "favorites"
    }
}
