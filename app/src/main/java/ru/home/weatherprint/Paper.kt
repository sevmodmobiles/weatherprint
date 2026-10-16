package ru.home.weatherprint

import android.content.Context

/**
 * Учёт бумаги по листам.
 *
 * Каждый лист помнит, на какую четверть он пойдёт дальше: 0–3 — лицевая сторона
 * (верх-лево, верх-право, низ-лево, низ-право), 4–7 — оборот, 8 — лист исписан полностью.
 * [tray] — листы в лотке подачи в порядке подачи (первый уйдёт в принтер первым),
 * [out] — листы в выходном лотке в порядке печати.
 *
 * Перекладка (обычная, когда лоток опустел, или ручная — в любой момент): стопку из выходного
 * лотка берут как есть и ставят в лоток подачи ПОЗАДИ оставшихся листов. Последний напечатанный
 * лист лежит в стопке сверху, становится ближе к вам и подаётся первым из переложенных — поэтому
 * переложенные листы идут в обратном порядке печати.
 */
class Paper(ctx: Context) {

    private val sp = ctx.getSharedPreferences("weatherprint", Context.MODE_PRIVATE)

    val tray: MutableList<Int>
    val out: MutableList<Int>

    init {
        if (!sp.contains("paperTray")) migrate()
        tray = parse(sp.getString("paperTray", ""))
        out = parse(sp.getString("paperOut", ""))
    }

    /** Сколько листов в пачке. */
    val sheets get() = sp.getInt("sheets", 30)

    /** Лоток опустел — ждём, пока человек переложит стопку. */
    val awaitingReload get() = tray.isEmpty()

    /** Четверть для следующего прогноза; null — лоток пуст. */
    val next: Int? get() = tray.firstOrNull()

    /** Сколько прогнозов ещё поместится на всей бумаге. */
    val daysLeft get() = (tray + out).sumOf { 8 - it }

    /** Отпечатан очередной прогноз: верхний лист из лотка переходит в выходной лоток. */
    fun printed() {
        if (tray.isEmpty()) return
        out.add(tray.removeAt(0) + 1)
        save()
    }

    /** Кусок стопки из выходного лотка с одинаковым действием — считая сверху. */
    class Segment(val count: Int, val next: Int) {
        val finished get() = next >= 8
        val flip get() = next == 4
    }

    /** Стопка из выходного лотка сверху вниз, сгруппированная по тому, что делать с листами. */
    fun segments(): List<Segment> {
        val top = out.reversed()
        val res = ArrayList<Segment>()
        for (n in top) {
            val last = res.lastOrNull()
            if (last != null && last.next == n) res[res.size - 1] = Segment(last.count + 1, n)
            else res.add(Segment(1, n))
        }
        return res
    }

    /** Применить перекладку: стопка из выходного лотка — позади листов в лотке, исписанные — заменить новыми. */
    fun reload() {
        val top = out.reversed()
        val finished = top.count { it >= 8 }
        tray.addAll(top.filter { it < 8 })
        repeat(finished) { tray.add(0) }
        out.clear()
        save()
    }

    /** Новая пачка: [n] чистых листов. */
    fun newPack(n: Int = sheets) {
        sp.edit().putInt("sheets", n.coerceIn(1, 100)).apply()
        tray.clear(); out.clear()
        repeat(n.coerceIn(1, 100)) { tray.add(0) }
        save()
    }

    /** Изменили число листов в пачке: добавляем чистые листы в конец лотка или убираем последние. */
    fun resize(n: Int) {
        val target = n.coerceIn(1, 100)
        sp.edit().putInt("sheets", target).apply()
        val diff = target - (tray.size + out.size)
        if (diff > 0) repeat(diff) { tray.add(0) }
        else repeat(-diff) { if (tray.size > 1) tray.removeAt(tray.size - 1) }
        save()
    }

    /** Ручной выбор четверти: все листы в лотке пойдут на [q]. */
    fun setNext(q: Int) {
        if (tray.isEmpty()) reload()
        for (i in tray.indices) tray[i] = q
        save()
    }

    // ---------- Тексты ----------

    fun quarterName(q: Int): String {
        val side = if (q >= 4) "оборот" else "лицо"
        val pos = listOf("верх-лево", "верх-право", "низ-лево", "низ-право")[q % 4]
        return "четверть №${q + 1} ($side, $pos)"
    }

    /** Что лежит в лотке подачи: «20 листов → четверть №2 …». */
    fun trayText(): String {
        if (tray.isEmpty()) return "Лоток подачи пуст."
        val groups = ArrayList<Pair<Int, Int>>()
        for (q in tray) {
            val last = groups.lastOrNull()
            if (last != null && last.second == q) groups[groups.size - 1] = last.first + 1 to q
            else groups.add(1 to q)
        }
        return "В лотке подачи ${tray.size} л.: " + groups.joinToString(", затем ") { (n, q) -> "$n → ${quarterName(q)}" }
    }

    /** Инструкция по перекладке стопки из выходного лотка. [manual] — лоток ещё не пуст. */
    fun reloadInstruction(manual: Boolean): String {
        val segs = segments()
        if (segs.isEmpty()) return "В выходном лотке нет напечатанных листов."
        val total = out.size
        val where = if (manual) "позади листов, которые уже лежат в лотке подачи" else "в задний лоток подачи"
        fun action(s: Segment) = when {
            s.finished -> "уберите — исписаны полностью; вместо них положите ${s.count} чистых в самый конец лотка"
            s.flip -> "переверните, как страницу книги, чистой стороной к себе"
            else -> "той же стороной, ничего не поворачивая"
        }
        if (segs.size == 1) {
            val s = segs[0]
            return when {
                s.finished && !manual -> "Все $total листов исписаны с двух сторон. Уберите их и заложите $total новых листов, затем нажмите «Бумага заложена»."
                s.finished -> "Достаньте из выходного лотка $total исписанных листов, уберите их и положите $total чистых листов $where."
                else -> "1. Достаньте из выходного лотка все листы ($total шт.).\n" +
                    "2. ${action(s).replaceFirstChar { it.uppercase() }}.\n" +
                    "3. Поставьте стопку $where.\n" +
                    "4. Нажмите «${if (manual) "Переложил" else "Бумага заложена"}»."
            }
        }
        val parts = segs.joinToString("\n") { "• ${it.count} л. — ${action(it)};" }
        return "1. Достаньте из выходного лотка всю стопку ($total шт.), не перемешивая.\n" +
            "2. Считая сверху, в ней:\n$parts\n" +
            "3. Сохраняя порядок (верхние листы — ближе к вам), поставьте стопку $where.\n" +
            "4. Нажмите «${if (manual) "Переложил" else "Бумага заложена"}»."
    }

    /** Короткий текст уведомления, когда лоток опустел. */
    fun reloadNotice(): Pair<String, String> {
        val segs = segments()
        return when {
            segs.all { it.finished } -> "Бумага израсходована полностью" to
                "Все листы исписаны с двух сторон. Заложите новую пачку и нажмите «Бумага заложена»."
            segs.size == 1 && segs[0].flip -> "Переверните пачку" to
                "Лицевая сторона заполнена. Достаньте листы, переверните пачку чистой стороной и нажмите «Бумага заложена»."
            segs.size == 1 -> "Положите листы обратно" to
                "Достаньте листы из выходного лотка и положите обратно той же стороной, не поворачивая."
            else -> "Переложите бумагу" to "Откройте приложение: в стопке листы на разных этапах, там написано, как их разложить."
        }
    }

    // ---------- Хранение ----------

    private fun save() {
        sp.edit().putString("paperTray", tray.joinToString(",")).putString("paperOut", out.joinToString(",")).apply()
    }

    private fun parse(s: String?): MutableList<Int> =
        s.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableList()

    /** Переход со старого счётчика «проход + номер листа». */
    private fun migrate() {
        val n = sp.getInt("sheets", 30)
        val pass = sp.getInt("pass", 0)
        val idx = sp.getInt("sheetIndex", 0).coerceIn(0, n)
        val t: List<Int>
        val o: List<Int>
        if (sp.getBoolean("awaitingReload", false)) {
            // Старый счётчик уже перешёл к следующему проходу (а после 8-го — обнулился)
            t = emptyList()
            o = List(n) { if (pass == 0) 8 else pass }
        } else {
            t = List(n - idx) { pass }
            o = List(idx) { pass + 1 }
        }
        sp.edit().putString("paperTray", t.joinToString(",")).putString("paperOut", o.joinToString(",")).apply()
    }
}
