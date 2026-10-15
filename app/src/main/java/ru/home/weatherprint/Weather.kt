package ru.home.weatherprint

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

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

/**
 * Прогноз из бесплатных сервисов без ключа. Open-Meteo доступен не везде (в России — часто только
 * через VPN), поэтому, если он не ответил, пробуем MET Norway, а затем wttr.in.
 * Все ответы приводятся к кодам погоды WMO, как у Open-Meteo.
 */
object WeatherApi {
    private const val UA = "WeatherPrint/1.0 github.com/sevmodmobiles/weatherprint"

    fun fetch(lat: Double, lon: Double): Weather {
        val errors = mutableListOf<String>()
        val sources = listOf<Pair<String, () -> Weather>>(
            "Open-Meteo" to { openMeteo(lat, lon) },
            "MET Norway" to { metNo(lat, lon) },
            "wttr.in" to { wttr(lat, lon) },
        )
        for ((name, load) in sources) {
            try {
                return load()
            } catch (e: Exception) {
                errors += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        error(errors.joinToString("; "))
    }

    private fun getJson(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 7_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun hourNow(): LocalDateTime = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)

    // ---------- Open-Meteo ----------

    private fun openMeteo(lat: Double, lon: Double): Weather {
        val json = JSONObject(getJson(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                "&hourly=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=4"
        ))
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
        val now = hourNow()
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
    }

    // ---------- MET Norway (api.met.no) ----------

    private fun metNo(lat: Double, lon: Double): Weather {
        val json = JSONObject(getJson(
            "https://api.met.no/weatherapi/locationforecast/2.0/complete" +
                "?lat=${"%.4f".format(Locale.US, lat)}&lon=${"%.4f".format(Locale.US, lon)}"
        ))
        val series = json.getJSONObject("properties").getJSONArray("timeseries")
        val zone = ZoneId.systemDefault()

        class Point(val time: LocalDateTime, val temp: Double, val hum: Double, val wind: Double,
                    val symbol1: String?, val symbol6: String?, val precip6: Double, val prob6: Int?)

        val points = (0 until series.length()).map { i ->
            val e = series.getJSONObject(i)
            val data = e.getJSONObject("data")
            val inst = data.getJSONObject("instant").getJSONObject("details")
            val n1 = data.optJSONObject("next_1_hours")
            val n6 = data.optJSONObject("next_6_hours")
            Point(
                time = Instant.parse(e.getString("time")).atZone(zone).toLocalDateTime(),
                temp = inst.getDouble("air_temperature"),
                hum = inst.optDouble("relative_humidity", 0.0),
                wind = inst.optDouble("wind_speed", 0.0),
                symbol1 = n1?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotEmpty() },
                symbol6 = n6?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotEmpty() },
                precip6 = n6?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0,
                prob6 = n6?.optJSONObject("details")?.takeIf { it.has("probability_of_precipitation") }
                    ?.optDouble("probability_of_precipitation")?.toInt(),
            )
        }
        val now = hourNow()
        val cur = points.lastOrNull { !it.time.isAfter(now) } ?: points.first()
        val curSymbol = cur.symbol1 ?: cur.symbol6 ?: "cloudy"

        val hours = points.filter { !it.time.isBefore(now) && it.symbol1 != null }.take(24)
            .map { HourForecast(it.time, metCode(it.symbol1!!), it.temp) }

        val days = points.groupBy { it.time.toLocalDate() }
            .filterKeys { !it.isBefore(now.toLocalDate()) }
            .toSortedMap().entries.take(4)
            .map { (date, list) ->
                // Значок дня — по прогнозу на день (с 12:00 или ближайший), осадки — максимум за день.
                val midday = list.filter { it.symbol6 != null }.minByOrNull { Math.abs(it.time.hour - 12) }
                val probs = list.mapNotNull { it.prob6 }
                val prob = if (probs.isNotEmpty()) probs.max() else {
                    val mm = list.maxOf { it.precip6 }
                    when { mm >= 2 -> 80; mm >= 0.5 -> 60; mm > 0 -> 30; else -> 0 }
                }
                DayForecast(
                    date = date,
                    code = metCode(midday?.symbol6 ?: list.first().let { it.symbol1 ?: it.symbol6 ?: "cloudy" }),
                    tMin = list.minOf { it.temp },
                    tMax = list.maxOf { it.temp },
                    precipProb = prob,
                )
            }

        return Weather(
            temp = cur.temp,
            feels = feelsLike(cur.temp, cur.wind),
            code = metCode(curSymbol),
            wind = cur.wind,
            days = days,
            humidity = Math.round(cur.hum).toInt(),
            isDay = !curSymbol.endsWith("_night"),
            hours = hours,
        )
    }

    /** Символ MET Norway («lightrainshowers_day») → код WMO. */
    private fun metCode(symbol: String): Int {
        val s = symbol.substringBefore('_')
        return when {
            "thunder" in s -> 95
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            s == "fog" -> 45
            "sleet" in s -> if (s.startsWith("heavy")) 69 else 68
            "snowshowers" in s -> if (s.startsWith("heavy")) 86 else 85
            "snow" in s -> when { s.startsWith("light") -> 71; s.startsWith("heavy") -> 75; else -> 73 }
            "rainshowers" in s -> when { s.startsWith("light") -> 80; s.startsWith("heavy") -> 82; else -> 81 }
            "rain" in s -> when { s.startsWith("light") -> 61; s.startsWith("heavy") -> 65; else -> 63 }
            else -> 3
        }
    }

    // ---------- wttr.in ----------

    private fun wttr(lat: Double, lon: Double): Weather {
        val json = JSONObject(getJson(
            "https://wttr.in/${"%.4f".format(Locale.US, lat)},${"%.4f".format(Locale.US, lon)}?format=j1&lang=ru"
        ))
        val cur = json.getJSONArray("current_condition").getJSONObject(0)
        val weather = json.getJSONArray("weather")

        // Почасовой прогноз у wttr.in — раз в 3 часа; растягиваем до каждого часа.
        class Slot(val time: LocalDateTime, val temp: Double, val code: Int)
        val slots = mutableListOf<Slot>()
        val days = (0 until weather.length()).map { i ->
            val d = weather.getJSONObject(i)
            val date = LocalDate.parse(d.getString("date"))
            val hourly = d.getJSONArray("hourly")
            var prob = 0
            val codes = mutableListOf<Int>()
            for (j in 0 until hourly.length()) {
                val h = hourly.getJSONObject(j)
                val hh = h.optString("time", "0").toInt() / 100
                val code = wwoCode(h.optInt("weatherCode", 116))
                slots += Slot(date.atTime(hh, 0), h.optDouble("tempC", 0.0), code)
                prob = maxOf(prob, h.optInt("chanceofrain", 0), h.optInt("chanceofsnow", 0))
                if (hh in 9..18) codes += code
            }
            DayForecast(
                date = date,
                code = codes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 3,
                tMin = d.optDouble("mintempC", 0.0),
                tMax = d.optDouble("maxtempC", 0.0),
                precipProb = prob,
            )
        }
        val now = hourNow()
        val hours = (0 until 24).mapNotNull { k ->
            val t = now.plusHours(k.toLong())
            val before = slots.lastOrNull { !it.time.isAfter(t) } ?: return@mapNotNull null
            val after = slots.firstOrNull { !it.time.isBefore(t) } ?: return@mapNotNull null
            val span = Duration.between(before.time, after.time).toMinutes().toDouble()
            val f = if (span > 0) Duration.between(before.time, t).toMinutes() / span else 0.0
            HourForecast(t, if (f < 0.5) before.code else after.code, before.temp + (after.temp - before.temp) * f)
        }

        val temp = cur.optDouble("temp_C", 0.0)
        val hour = LocalTime.now().hour
        return Weather(
            temp = temp,
            feels = cur.optDouble("FeelsLikeC", temp),
            code = wwoCode(cur.optInt("weatherCode", 116)),
            wind = cur.optDouble("windspeedKmph", 0.0) / 3.6,
            days = days,
            humidity = cur.optInt("humidity", 0),
            isDay = hour in 7..19,
            hours = hours,
        )
    }

    /** Код погоды WorldWeatherOnline (его отдаёт wttr.in) → код WMO. */
    private fun wwoCode(c: Int): Int = when (c) {
        113 -> 0
        116 -> 2
        119, 122 -> 3
        143, 248, 260 -> 45
        176, 353 -> 80
        356 -> 81
        359 -> 82
        263, 266 -> 51
        185, 281, 284 -> 56
        293, 296 -> 61
        299, 302 -> 63
        305, 308 -> 65
        311, 314 -> 66
        182, 317, 320, 362, 365 -> 68
        179, 323, 326, 368 -> 71
        329, 332 -> 73
        227, 230, 335, 338 -> 75
        350, 374, 377 -> 77
        371 -> 86
        200, 386, 389, 392, 395 -> 95
        else -> 3
    }

    /** Ощущаемая температура: при холоде учитываем ветер (формула wind chill), иначе — как есть. */
    private fun feelsLike(t: Double, windMs: Double): Double {
        val v = windMs * 3.6
        if (t > 10 || v < 4.8) return t
        val p = Math.pow(v, 0.16)
        return 13.12 + 0.6215 * t - 11.37 * p + 0.3965 * t * p
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
        68, 69 -> "Мокрый снег"
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
