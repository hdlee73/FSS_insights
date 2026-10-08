package io.github.hdlee73.financenewsradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 오늘의 브리핑 머리말에 쓰는 서울 날씨 한 줄. */
data class Weather(val emoji: String, val summary: String, val temp: Int, val min: Int, val max: Int, val rainChance: Int?)

/** API 키가 필요 없는 Open-Meteo로 서울 날씨를 읽는다. 위치 권한은 쓰지 않는다. */
object WeatherClient {
    private const val URL_TEXT = "https://api.open-meteo.com/v1/forecast?latitude=37.5665&longitude=126.9780" +
        "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=Asia%2FSeoul&forecast_days=1"

    suspend fun fetch(): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(URL_TEXT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            try {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")
                val (emoji, text) = describe(current.getInt("weather_code"))
                Weather(
                    emoji, text,
                    temp = Math.round(current.getDouble("temperature_2m")).toInt(),
                    min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                    max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    rainChance = daily.optJSONArray("precipitation_probability_max")?.let { if (it.isNull(0)) null else it.getInt(0) }
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** WMO 날씨 코드를 그림 문자와 짧은 설명으로. */
    internal fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "맑음"
        1 -> "🌤️" to "대체로 맑음"
        2 -> "⛅" to "구름 조금"
        3 -> "☁️" to "흐림"
        45, 48 -> "🌫️" to "안개"
        in 51..57 -> "🌦️" to "이슬비"
        in 61..67 -> "🌧️" to "비"
        in 71..77, 85, 86 -> "❄️" to "눈"
        in 80..82 -> "🌦️" to "소나기"
        in 95..99 -> "⛈️" to "뇌우"
        else -> "🌡️" to "날씨"
    }
}
