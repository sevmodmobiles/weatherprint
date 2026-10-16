package ru.home.weatherprint

import android.content.Context
import java.io.File

/**
 * Список своих принтеров (дом, дача и т. п.). У каждого принтера свои настройки, уникальный номер,
 * бумага и отметка «сегодня уже печатали» — они хранятся с префиксом «p_<id>_».
 * Активный — тот, что показан на экранах и с которым работают «Напечатать сейчас» и обслуживание.
 */
object Printers {

    private const val IDS = "printerIds"
    private const val ACTIVE = "activePrinter"

    /** Ключи, которые принадлежат конкретному принтеру. */
    val SCOPED = listOf(
        "host", "printerName", "printerModel", "path", "protocol", "identity", "label",
        "lastPrintDate", "lastPrintAt", "lastResult",
        "sheets", "paperTray", "paperOut", "pass", "sheetIndex", "awaitingReload",
    )

    fun key(id: String, name: String) = "p_${id}_$name"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("weatherprint", Context.MODE_PRIVATE)

    /** Все принтеры по порядку добавления. Всегда есть хотя бы один (возможно, ещё «не выбранный»). */
    fun ids(ctx: Context): List<String> {
        val sp = sp(ctx)
        if (!sp.contains(IDS)) migrate(ctx)
        return sp.getString(IDS, "").orEmpty().split(',').filter { it.isNotBlank() }
    }

    fun active(ctx: Context): String {
        val all = ids(ctx)
        val a = sp(ctx).getString(ACTIVE, null)
        return if (a != null && a in all) a else all.first()
    }

    fun setActive(ctx: Context, id: String) = sp(ctx).edit().putString(ACTIVE, id).apply()

    /** Принтеры, которые уже выбраны (у «пустого» места ещё нет имени). */
    fun configured(ctx: Context): List<String> = ids(ctx).filter { Prefs(ctx, it).printerName.isNotEmpty() }

    /** Новое место для принтера с чистой пачкой бумаги. */
    fun add(ctx: Context): String {
        val all = ids(ctx)
        val id = ((all.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        sp(ctx).edit().putString(IDS, (all + id).joinToString(",")).apply()
        Paper(ctx, id).newPack(30)
        return id
    }

    /** Удаляет принтер со всеми его данными. Последний не удаляется, а очищается. */
    fun remove(ctx: Context, id: String) {
        val all = ids(ctx)
        val e = sp(ctx).edit()
        SCOPED.forEach { e.remove(key(id, it)) }
        if (all.size > 1) {
            e.putString(IDS, (all - id).joinToString(","))
            if (active(ctx) == id) e.putString(ACTIVE, (all - id).first())
        }
        e.apply()
        PrinterInfo.iconFile(ctx, id).delete()
        if (all.size == 1) Paper(ctx, id).newPack(30)
    }

    /** Переход с одного принтера (старые версии) на список: всё переносится в принтер №1. */
    private fun migrate(ctx: Context) {
        val sp = sp(ctx)
        val e = sp.edit()
        for (k in SCOPED) {
            if (!sp.contains(k)) continue
            when (val v = sp.all[k]) {
                is String -> e.putString(key("1", k), v)
                is Int -> e.putInt(key("1", k), v)
                is Boolean -> e.putBoolean(key("1", k), v)
                is Long -> e.putLong(key("1", k), v)
            }
        }
        e.putString(IDS, "1").putString(ACTIVE, "1").apply()
        File(ctx.filesDir, "printer_icon.png").takeIf { it.exists() }?.renameTo(PrinterInfo.iconFile(ctx, "1"))
    }
}
