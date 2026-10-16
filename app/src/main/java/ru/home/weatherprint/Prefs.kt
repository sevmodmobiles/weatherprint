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

    /** Модель принтера для показа («Epson L805»); пусто — ещё не определяли. */
    var printerModel: String
        get() = sp.getString("printerModel", "")!!
        set(v) = sp.edit().putString("printerModel", v).apply()

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

    /** Печатать ли по расписанию. */
    var scheduleEnabled: Boolean
        get() = sp.getBoolean("scheduleEnabled", true)
        set(v) = sp.edit().putBoolean("scheduleEnabled", v).apply()

    /** Время печати, минуты от полуночи. По умолчанию — бывшая настройка «не раньше, часов». */
    var printMinutes: Int
        get() = sp.getInt("printMinutes", sp.getInt("fromHour", 17) * 60)
        set(v) = sp.edit().putInt("printMinutes", v.coerceIn(0, 24 * 60 - 1)).apply()

    /** Дата последней печати, yyyy-MM-dd. */
    var lastPrintDate: String
        get() = sp.getString("lastPrintDate", "")!!
        set(v) = sp.edit().putString("lastPrintDate", v).apply()

    /** Время последней успешной печати, «yyyy-MM-ddTHH:mm»; пусто — ещё не печатали. */
    var lastPrintAt: String
        get() = sp.getString("lastPrintAt", null)
            // Из старых версий — достаём из текста «Напечатано 2026-10-16 в 06:00 (…)»
            ?: Regex("Напечатано (\\d{4}-\\d{2}-\\d{2}) в (\\d{2}:\\d{2})").find(lastResult)
                ?.let { "${it.groupValues[1]}T${it.groupValues[2]}" }.orEmpty()
        set(v) = sp.edit().putString("lastPrintAt", v).apply()

    var lastResult: String
        get() = sp.getString("lastResult", "Ещё не печатали")!!
        set(v) = sp.edit().putString("lastResult", v).apply()
}
