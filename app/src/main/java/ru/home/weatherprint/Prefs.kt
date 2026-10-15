package ru.home.weatherprint

import android.content.Context

/**
 * Настройки и счётчик бумаги.
 *
 * Схема расхода: пачка из [sheets] листов проходит 8 раз.
 * Проходы 1–4 — лицевая сторона (четверти: верх-лево, верх-право, низ-лево, низ-право),
 * проходы 5–8 — оборот (те же четверти). Лист крутить не нужно: позицию сдвигает приложение.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("weatherprint", Context.MODE_PRIVATE)

    var printerHost: String
        get() = sp.getString("host", "192.168.1.50")!!
        set(v) = sp.edit().putString("host", v.trim()).apply()

    /** Имя принтера в сети (mDNS). По нему принтер ищется заново, если сменился IP. */
    var printerName: String
        get() = sp.getString("printerName", "")!!
        set(v) = sp.edit().putString("printerName", v).apply()

    var ippPath: String
        get() = sp.getString("path", "/ipp/print")!!
        set(v) = sp.edit().putString("path", v.trim().let { if (it.startsWith("/")) it else "/$it" }).apply()

    /** Как печатать: "ipp" (AirPrint/Mopria) или "escpr" (Epson без AirPrint, порт 9100). */
    var protocol: String
        get() = sp.getString("protocol", "ipp")!!
        set(v) = sp.edit().putString("protocol", v).apply()

    var city: String
        get() = sp.getString("city", "Санкт-Петербург")!!
        set(v) = sp.edit().putString("city", v.trim()).apply()

    var lat: Double
        get() = sp.getString("lat", "59.9386")!!.toDoubleOrNull() ?: 59.9386
        set(v) = sp.edit().putString("lat", v.toString()).apply()

    var lon: Double
        get() = sp.getString("lon", "30.3141")!!.toDoubleOrNull() ?: 30.3141
        set(v) = sp.edit().putString("lon", v.toString()).apply()

    /** Сколько листов в пачке. */
    var sheets: Int
        get() = sp.getInt("sheets", 30)
        set(v) = sp.edit().putInt("sheets", v.coerceIn(1, 100)).apply()

    /** Не печатать раньше этого часа (чтобы печать была, когда вы дома). */
    var fromHour: Int
        get() = sp.getInt("fromHour", 17)
        set(v) = sp.edit().putInt("fromHour", v.coerceIn(0, 23)).apply()

    /** Номер листа в текущем проходе, 0 .. sheets-1. */
    var sheetIndex: Int
        get() = sp.getInt("sheetIndex", 0)
        set(v) = sp.edit().putInt("sheetIndex", v).apply()

    /** Номер прохода, 0..7. 0–3 лицевая сторона, 4–7 оборот. */
    var pass: Int
        get() = sp.getInt("pass", 0)
        set(v) = sp.edit().putInt("pass", v).apply()

    /** true — пачка закончилась, ждём, пока пользователь переложит бумагу и нажмёт кнопку. */
    var awaitingReload: Boolean
        get() = sp.getBoolean("awaitingReload", false)
        set(v) = sp.edit().putBoolean("awaitingReload", v).apply()

    /** Дата последней печати, yyyy-MM-dd. */
    var lastPrintDate: String
        get() = sp.getString("lastPrintDate", "")!!
        set(v) = sp.edit().putString("lastPrintDate", v).apply()

    var lastResult: String
        get() = sp.getString("lastResult", "Ещё не печатали")!!
        set(v) = sp.edit().putString("lastResult", v).apply()

    val quadrant: Int get() = pass % 4
    val backSide: Boolean get() = pass >= 4
    val daysLeft: Int get() = (7 - pass) * sheets + (sheets - sheetIndex)
}
