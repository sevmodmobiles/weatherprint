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
    private lateinit var sheetNo: EditText
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
        sheetNo.setText((prefs.sheetIndex + 1).toString())
        refresh()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun build() {
        // Бумага
        sectionTitle("Бумага")
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
            prefs.awaitingReload = false
            refresh()
        }

        sheet = SheetView(this).apply {
            onPick = { idx -> pickQuarter(idx) }
        }
        paper.addView(sheet, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        label("Синяя четверть — сюда ляжет следующий прогноз. Нажмите на другую, чтобы печатать туда.",
            paper, 12f, Ui.SUB).setPadding(0, dp(8), 0, 0)

        val num = InputType.TYPE_CLASS_NUMBER
        sheets = field(paper, "Листов в пачке", prefs.sheets.toString(), num)
        sheetNo = field(paper, "Текущий лист в проходе", (prefs.sheetIndex + 1).toString(), num)
        button("Новая пачка: начать сначала", paper, filled = false) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Новая пачка")
                .setMessage("Счётчик начнётся с первого листа, первой четверти лицевой стороны.")
                .setPositiveButton("Начать") { _, _ ->
                    prefs.pass = 0; prefs.sheetIndex = 0; prefs.awaitingReload = false
                    sheetNo.setText("1")
                    refresh()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Как расходуется бумага
        sectionTitle("Как печатается")
        val how = card()
        label(
            "Пачка проходит через принтер 8 раз: сначала 4 четверти лицевой стороны " +
                "(верх-лево, верх-право, низ-лево, низ-право), потом те же 4 четверти на обороте. " +
                "Лист крутить не нужно — позицию сдвигает само приложение.\n\n" +
                "Когда пачка закончилась, придёт уведомление:\n" +
                "• после проходов 1–3 и 5–7 — переложите листы обратно той же стороной;\n" +
                "• после прохода 4 — переверните пачку чистой стороной;\n" +
                "• после прохода 8 — заложите новую бумагу.\n\n" +
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
        paperStatus.text = PaperText.status(prefs)
        sheet.pass = prefs.pass
        reloadBox.visibility = if (prefs.awaitingReload) View.VISIBLE else View.GONE
        if (prefs.awaitingReload) reloadText.text = PaperText.reloadInstruction(prefs)
    }

    /** Ручной выбор четверти — например, если пачку переложили раньше времени. */
    private fun pickQuarter(idx: Int) {
        if (idx == prefs.pass) return
        val side = if (idx >= 4) "оборота" else "лицевой стороны"
        android.app.AlertDialog.Builder(this)
            .setTitle("Четверть №${idx + 1}")
            .setMessage("Печатать следующие прогнозы в четверть №${idx + 1} ($side)? Номер листа сохранится.")
            .setPositiveButton("Да") { _, _ ->
                prefs.pass = idx
                prefs.awaitingReload = false
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun save() {
        sheets.text.toString().toIntOrNull()?.let { prefs.sheets = it }
        sheetNo.text.toString().toIntOrNull()?.let { prefs.sheetIndex = (it - 1).coerceIn(0, prefs.sheets - 1) }
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
