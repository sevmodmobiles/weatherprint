package ru.home.weatherprint

import android.content.Context

/**
 * Настройки и счётчик бумаги.
 *
 * Схема расхода: пачка из [sheets] листов проходит 8 раз.
 * Проходы 1–4 — лицевая сторона (четверти: верх-лево, верх-право, низ-лево, низ-право),
 * проходы 5–8 — оборот (те же четверти). Лист крутить не нужно: позицию сдвигает приложение.
 */
class Prefs(context: Context, printerId: String? = null) {
    private val sp = context.getSharedPreferences("weatherprint", Context.MODE_PRIVATE)

    /** Принтер, к которому относятся его настройки (адрес, модель, последняя печать). По умолчанию — активный. */
    val printerId: String = printerId ?: Printers.active(context)

    private fun k(name: String) = Printers.key(this.printerId, name)

    /** Уникальный номер принтера: «mac:…», «uuid:…» или «sn:…»; пусто — неизвестен. */
    var printerIdentity: String
        get() = sp.getString(k("identity"), "")!!
        set(v) = sp.edit().putString(k("identity"), v).apply()

    /** Своё название принтера («Дом», «Дача»); пусто — показываем модель. */
    var printerLabel: String
        get() = sp.getString(k("label"), "")!!
        set(v) = sp.edit().putString(k("label"), v.trim()).apply()

    /** Как показывать принтер: своё название, модель или имя в сети. */
    val displayName: String
        get() = printerLabel.ifEmpty { printerModel }.ifEmpty { printerName }.ifEmpty { "Принтер не выбран" }

    var printerHost: String
        get() = sp.getString(k("host"), "192.168.1.50")!!
        set(v) = sp.edit().putString(k("host"), v.trim()).apply()

    /** Имя принтера в сети (mDNS). По нему принтер ищется заново, если сменился IP. */
    var printerName: String
        get() = sp.getString(k("printerName"), "")!!
        set(v) = sp.edit().putString(k("printerName"), v).apply()

    /** Модель принтера для показа («Epson L805»); пусто — ещё не определяли. */
    var printerModel: String
        get() = sp.getString(k("printerModel"), "")!!
        set(v) = sp.edit().putString(k("printerModel"), v).apply()

    var ippPath: String
        get() = sp.getString(k("path"), "/ipp/print")!!
        set(v) = sp.edit().putString(k("path"), v.trim().let { if (it.startsWith("/")) it else "/$it" }).apply()

    /** Как печатать: "ipp" (AirPrint/Mopria) или "escpr" (Epson без AirPrint, порт 9100). */
    var protocol: String
        get() = sp.getString(k("protocol"), "ipp")!!
        set(v) = sp.edit().putString(k("protocol"), v).apply()

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
        get() = sp.getInt(k("sheets"), 30)
        set(v) = sp.edit().putInt(k("sheets"), v.coerceIn(1, 100)).apply()

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
        get() = sp.getString(k("lastPrintDate"), "")!!
        set(v) = sp.edit().putString(k("lastPrintDate"), v).apply()

    /** Время последней успешной печати, «yyyy-MM-ddTHH:mm»; пусто — ещё не печатали. */
    var lastPrintAt: String
        get() = sp.getString(k("lastPrintAt"), null)
            // Из старых версий — достаём из текста «Напечатано 2026-10-16 в 06:00 (…)»
            ?: Regex("Напечатано (\\d{4}-\\d{2}-\\d{2}) в (\\d{2}:\\d{2})").find(lastResult)
                ?.let { "${it.groupValues[1]}T${it.groupValues[2]}" }.orEmpty()
        set(v) = sp.edit().putString(k("lastPrintAt"), v).apply()

    /** Погодные фразы, уже напечатанные в этом году, — чтобы не повторять. */
    var usedPhrases: Set<String>
        get() {
            val year = java.time.LocalDate.now().year
            if (sp.getInt("phrasesYear", 0) != year) return emptySet()
            return sp.getString("phrasesUsed", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        }
        set(v) = sp.edit().putInt("phrasesYear", java.time.LocalDate.now().year)
            .putString("phrasesUsed", v.joinToString("\n")).apply()

    var lastResult: String
        get() = sp.getString(k("lastResult"), "Ещё не печатали")!!
        set(v) = sp.edit().putString(k("lastResult"), v).apply()
}
