package ru.home.weatherprint

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Настройки приложения: бумага и схема печати по четвертям, местоположение, обновления. */
class SettingsActivity : BaseActivity() {

    private lateinit var paperStatus: TextView
    private lateinit var sheet: SheetView
    private lateinit var sheets: EditText
    private lateinit var manualBtn: TextView
    private lateinit var manualText: TextView
    private lateinit var reloadBox: LinearLayout
    private lateinit var reloadText: TextView
    private lateinit var city: EditText
    private lateinit var lat: EditText
    private lateinit var lon: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Настройки", back = true)
        build()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Пока экран был закрыт, могла пройти печать — обновляем счётчик
        refresh()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun build() {
        // Бумага
        // У каждого принтера своя бумага — показываем, о каком речь
        sectionTitle(if (Printers.configured(this).size > 1) "Бумага · ${prefs.displayName}" else "Бумага")
        val paper = card()
        paperStatus = label("", paper, 15f)
        reloadBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.WARN_SOFT, dpf(14f))
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        paper.addView(reloadBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        label("Пора переложить бумагу", reloadBox, 15f, Ui.WARN, bold = true)
        reloadText = label("", reloadBox, 14f).apply { setPadding(0, dp(4), 0, 0) }
        button("Бумага заложена", reloadBox) {
            Paper(this).reload()
            refresh()
        }

        sheet = SheetView(this).apply {
            onPick = { idx -> pickQuarter(idx) }
        }
        paper.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        label("Синяя четверть — сюда ляжет следующий прогноз. Нажмите на другую, чтобы печатать туда.",
            paper, 12f, Ui.SUB).setPadding(0, dp(8), 0, 0)

        val num = InputType.TYPE_CLASS_NUMBER
        sheets = field(paper, "Листов в пачке", Paper(this).sheets.toString(), num)
        button("Новая пачка: начать сначала", paper, filled = false) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Новая пачка")
                .setMessage("Все листы будут считаться чистыми: печать начнётся с первой четверти лицевой стороны.")
                .setPositiveButton("Начать") { _, _ ->
                    Paper(this).newPack(sheets.text.toString().toIntOrNull() ?: Paper(this).sheets)
                    refresh()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Ручная перекладка — в любой момент, не дожидаясь, пока лоток опустеет
        sectionTitle("Переложить напечатанные листы")
        val manual = card()
        manualText = label("", manual, 14f)
        manualBtn = button("Переложить сейчас", manual) { manualReload() }

        // Как расходуется бумага
        sectionTitle("Как печатается")
        val how = card()
        label(
            "Пачка проходит через принтер 8 раз: сначала 4 четверти лицевой стороны " +
                "(верх-лево, верх-право, низ-лево, низ-право), потом те же 4 четверти на обороте. " +
                "Лист крутить не нужно — позицию сдвигает само приложение.\n\n" +
                "Когда лоток подачи опустеет, придёт уведомление:\n" +
                "• после четвертей 1–3 и 5–7 — переложите листы обратно той же стороной;\n" +
                "• после четверти 4 — переверните пачку чистой стороной;\n" +
                "• после четверти 8 — заложите новую бумагу.\n\n" +
                "Не обязательно ждать: напечатанные листы можно переложить в любой момент — " +
                "поставить их позади тех, что ещё в лотке, и отметить это в разделе «Переложить напечатанные листы». " +
                "Приложение помнит каждый лист и само рассчитает, на какую четверть печатать.\n\n" +
                "Слева у прогноза печатается цветной корешок — 6 тонких линий всеми чернилами во всю высоту. " +
                "Он прокачивает все дюзы головки, чтобы чернила не засыхали.",
            how, 14f, Ui.TEXT
        )

        // Местоположение
        sectionTitle("Местоположение")
        val loc = card()
        city = field(loc, "Город (для заголовка)", prefs.city)
        val coord = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        lat = field(loc, "Широта", prefs.lat.toString(), coord)
        lon = field(loc, "Долгота", prefs.lon.toString(), coord)

        // Приложение
        sectionTitle("Приложение")
        val app = card(pad = 8)
        listItem(app, Icon.DOWNLOAD, "Проверить обновления",
            "WeatherPrint ${Updater.currentVersionName(this)}") { checkUpdate() }
    }

    private fun refresh() {
        val p = Paper(this)
        paperStatus.text = p.trayText() +
            "\nВ выходном лотке: ${p.out.size} л." +
            "\nБумаги хватит примерно на ${p.daysLeft} дн."
        sheet.pass = p.next ?: p.segments().firstOrNull()?.next?.takeIf { it < 8 } ?: 0
        reloadBox.visibility = if (p.awaitingReload) View.VISIBLE else View.GONE
        if (p.awaitingReload) reloadText.text = p.reloadInstruction(manual = false)

        val canManual = !p.awaitingReload && p.out.isNotEmpty()
        manualText.text = when {
            p.awaitingReload -> "Лоток подачи пуст — воспользуйтесь инструкцией в разделе «Бумага» выше."
            p.out.isEmpty() -> "Напечатанных листов в выходном лотке пока нет."
            else -> "В выходном лотке ${p.out.size} л. Их можно уже сейчас поставить позади листов в лотке подачи — " +
                "тогда не придётся подходить к принтеру, когда лоток опустеет."
        }
        manualBtn.isEnabled = canManual
        manualBtn.alpha = if (canManual) 1f else 0.4f
    }

    /** Ручная перекладка: инструкция и подтверждение. */
    private fun manualReload() {
        val p = Paper(this)
        android.app.AlertDialog.Builder(this)
            .setTitle("Переложить напечатанные листы")
            .setMessage(p.reloadInstruction(manual = true))
            .setPositiveButton("Переложил") { _, _ ->
                Paper(this).reload()
                toast("Учтено: ${Paper(this).trayText()}")
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Ручной выбор четверти — например, если пачку переложили раньше времени. */
    private fun pickQuarter(idx: Int) {
        if (idx == Paper(this).next) return
        val side = if (idx >= 4) "оборота" else "лицевой стороны"
        android.app.AlertDialog.Builder(this)
            .setTitle("Четверть №${idx + 1}")
            .setMessage("Все листы в лотке подачи пойдут на четверть №${idx + 1} ($side)?")
            .setPositiveButton("Да") { _, _ ->
                Paper(this).setNext(idx)
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun save() {
        sheets.text.toString().toIntOrNull()?.let { n ->
            val paper = Paper(this)
            if (n != paper.sheets) paper.resize(n)
        }
        prefs.city = city.text.toString()
        lat.text.toString().replace(',', '.').toDoubleOrNull()?.let { prefs.lat = it }
        lon.text.toString().replace(',', '.').toDoubleOrNull()?.let { prefs.lon = it }
    }

    private fun checkUpdate() {
        toast("Проверяю…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.checkForUpdate(this@SettingsActivity) } }
            val rel = r.getOrNull()
            when {
                rel != null -> android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Доступна версия ${rel.name}")
                    .setMessage("Кнопка «Обновить» — на главном экране.")
                    .setPositiveButton("На главный") { _, _ -> finish() }
                    .setNegativeButton("Позже", null)
                    .show()
                r.isFailure -> toast("Не удалось проверить: ${r.exceptionOrNull()?.message}")
                else -> toast("У вас последняя версия")
            }
        }
    }
}
