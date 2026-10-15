package ru.home.weatherprint

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val tMin: Double,
    val tMax: Double,
    val precipProb: Int,
)

data class HourForecast(
    val time: LocalDateTime,
    val code: Int,
    val temp: Double,
)

data class Weather(
    val temp: Double,
    val feels: Double,
    val code: Int,
    val wind: Double,
    val days: List<DayForecast>,
    val humidity: Int = 0,
    val isDay: Boolean = true,
    val hours: List<HourForecast> = emptyList(),
)

/** Бесплатный прогноз Open-Meteo, ключ не нужен. */
object WeatherApi {
    fun fetch(lat: Double, lon: Double): Weather {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                "&hourly=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=4"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) error("Open-Meteo HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val cur = json.getJSONObject("current")
            val d = json.getJSONObject("daily")
            val times = d.getJSONArray("time")
            val days = (0 until times.length()).map { i ->
                DayForecast(
                    date = LocalDate.parse(times.getString(i)),
                    code = d.getJSONArray("weather_code").optInt(i, 0),
                    tMin = d.getJSONArray("temperature_2m_min").optDouble(i, 0.0),
                    tMax = d.getJSONArray("temperature_2m_max").optDouble(i, 0.0),
                    precipProb = d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                )
            }
            val h = json.getJSONObject("hourly")
            val hTimes = h.getJSONArray("time")
            val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
            val hours = (0 until hTimes.length()).map { i ->
                HourForecast(
                    time = LocalDateTime.parse(hTimes.getString(i)),
                    code = h.getJSONArray("weather_code").optInt(i, 0),
                    temp = h.getJSONArray("temperature_2m").optDouble(i, 0.0),
                )
            }.filter { !it.time.isBefore(now) }.take(24)
            return Weather(
                humidity = cur.optInt("relative_humidity_2m", 0),
                isDay = cur.optInt("is_day", 1) == 1,
                hours = hours,
                temp = cur.getDouble("temperature_2m"),
                feels = cur.getDouble("apparent_temperature"),
                code = cur.getInt("weather_code"),
                wind = cur.getDouble("wind_speed_10m"),
                days = days,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Коды погоды WMO → текст. */
    fun describe(code: Int): String = when (code) {
        0 -> "Ясно"
        1 -> "Малооблачно"
        2 -> "Переменная облачность"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Ледяная морось"
        61 -> "Небольшой дождь"
        63 -> "Дождь"
        65 -> "Сильный дождь"
        66, 67 -> "Ледяной дождь"
        71 -> "Небольшой снег"
        73 -> "Снег"
        75 -> "Сильный снег"
        77 -> "Снежная крупа"
        80, 81 -> "Ливень"
        82 -> "Сильный ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        else -> "—"
    }

    fun fmtTemp(t: Double): String {
        val r = Math.round(t).toInt()
        return when {
            r > 0 -> "+$r°"
            r < 0 -> "−${-r}°"
            else -> "0°"
        }
    }
}
