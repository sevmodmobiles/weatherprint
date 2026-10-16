package ru.home.weatherprint

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Экран принтера: состояние, печать, предпросмотр, обслуживание и подключение. */
class PrinterActivity : BaseActivity() {

    private lateinit var nameText: TextView
    private lateinit var infoText: TextView
    private lateinit var statusLine: TextView
    private lateinit var detailsText: TextView
    private lateinit var host: EditText
    private lateinit var path: EditText
    private lateinit var pathBox: LinearLayout

    private val escpr get() = prefs.protocol == "escpr"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Принтер", back = true)
        build()
        refresh()
        checkPrinter()
    }

    override fun onPause() {
        super.onPause()
        saveConnection()
    }

    private fun build() {
        // Шапка: принтер и его состояние
        val head = card(pad = 20)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(iconBadge(Icon.PRINTER, Ui.ACCENT, Ui.ACCENT_SOFT, 56))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        nameText = TextView(this).apply {
            textSize = 19f; setTextColor(Ui.TEXT); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        }
        infoText = TextView(this).apply { textSize = 14f; setTextColor(Ui.SUB); setPadding(0, dp(2), 0, 0) }
        statusLine = TextView(this).apply { textSize = 14f; setTextColor(Ui.SUB); setPadding(0, dp(4), 0, 0) }
        col.addView(nameText); col.addView(infoText); col.addView(statusLine)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(row)
        detailsText = label("", head, 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        button("Проверить связь", head, filled = false) { checkPrinter(details = true) }

        sectionTitle("Печать")
        val print = card(pad = 8)
        listItem(print, Icon.PRINTER, "Напечатать сейчас", "Прогноз на следующую четверть листа") { runPrint() }
        listItem(print, Icon.EYE, "Предпросмотр печати", "Как выйдет прогноз и расход чернил") { showPreview() }

        sectionTitle("Обслуживание")
        val m = card(pad = 8)
        val hint = if (escpr) null else "Только для Epson с печатью ESC/P-R"
        listItem(m, Icon.LINES, "Проверка дюз", hint ?: "Шаблон из линий каждого цвета", enabled = escpr) { maintenance(Maint.NOZZLE) }
        listItem(m, Icon.DROP, "Прочистка печатающей головки", hint ?: "1–2 минуты, расходует чернила", enabled = escpr) { maintenance(Maint.CLEAN) }
        listItem(m, Icon.PAPER, "Тест качества печати", "Целый лист: цвета, градиенты, линии, текст") { maintenance(Maint.TEST) }

        sectionTitle("Подключение")
        val conn = card(pad = 8)
        listItem(conn, Icon.SEARCH, "Найти принтер в сети", "Автоматически, без ввода IP") { findPrinter() }
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        conn.addView(manual)
        host = field(manual, "IP-адрес принтера", prefs.printerHost,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        pathBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        manual.addView(pathBox)
        path = field(pathBox, "Путь IPP (обычно /ipp/print)", prefs.ippPath)
    }

    private fun refresh() {
        nameText.text = prefs.printerName.ifEmpty { "Принтер не выбран" }
        infoText.text = when {
            prefs.printerName.isEmpty() -> "Найдите принтер в разделе «Подключение»"
            escpr -> "Epson · ESC/P-R, порт 9100 · ${prefs.printerHost}"
            else -> "AirPrint / IPP · ${prefs.printerHost}"
        }
        if (host.text.toString() != prefs.printerHost) host.setText(prefs.printerHost)
        if (path.text.toString() != prefs.ippPath) path.setText(prefs.ippPath)
        pathBox.visibility = if (escpr) View.GONE else View.VISIBLE
    }

    private fun saveConnection() {
        prefs.printerHost = host.text.toString()
        if (!escpr) prefs.ippPath = path.text.toString()
    }

    private fun checkPrinter(details: Boolean = false) {
        saveConnection()
        statusLine.text = statusText(null, "Проверяю связь…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val lan = LocalNet.route(this@PrinterActivity)
                    if (escpr) {
                        EscprClient(prefs.printerHost, lan).ping()
                        "Печать на «родном» языке Epson через порт 9100."
                    } else {
                        val a = IppClient(prefs.printerHost, prefs.ippPath, lan).getAttributes()
                        val model = a["printer-make-and-model"]?.firstOrNull() ?: "?"
                        val formats = a["document-format-supported"].orEmpty()
                        val jpeg = if ("image/jpeg" in formats) "JPEG поддерживается ✓" else "JPEG НЕ найден в списке форматов"
                        "Модель: $model\n$jpeg\nФорматы: ${formats.joinToString()}"
                    }
                }
            }
            val ok = r.isSuccess
            statusLine.text = statusText(ok, if (ok) "В сети" else "Не отвечает")
            if (details || !ok) {
                detailsText.text = r.getOrElse { "Ошибка: ${it.message}" }
                detailsText.visibility = View.VISIBLE
            }
        }
    }

    private fun runPrint() {
        saveConnection()
        toast("Отправляю на принтер…")
        lifecycleScope.launch {
            val (_, msg) = withContext(Dispatchers.IO) { PrintJob.run(this@PrinterActivity, force = true) }
            toast(msg)
        }
    }

    /** Показывает, как выйдет прогноз, и примерный расход каждого из 6 чернил — без бумаги и чернил. */
    private fun showPreview() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val wx = withContext(Dispatchers.IO) { WeatherApi.fetch(prefs.lat, prefs.lon) }
                    val pv = PageRenderer.renderPreview(wx, prefs.city, PrintJob.footerText(prefs))
                    pv to InkCheck.analyze(pv.bitmap, pv.contentTop, pv.contentBottom)
                }
            }
            val (pv, inks) = r.getOrElse {
                toast("Не удалось получить погоду: ${it.message}")
                return@launch
            }

            val box = LinearLayout(this@PrinterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            box.addView(android.widget.ImageView(this@PrinterActivity).apply {
                setImageBitmap(pv.bitmap)
                adjustViewBounds = true
                background = Ui.rounded(Color.WHITE, dpf(8f), dp(1), Ui.LINE)
                setPadding(dp(1), dp(1), dp(1), dp(1))
            })
            label("Так прогноз выйдет на четверти листа (${PrintJob.footerText(prefs)}).", box, 13f, Ui.SUB)
                .setPadding(0, dp(8), 0, dp(12))
            label("Чернила (оценка)", box, 16f, bold = true)
            val sb = android.text.SpannableStringBuilder()
            for (ink in inks) {
                val start = sb.length
                sb.append("● ")
                sb.setSpan(android.text.style.ForegroundColorSpan(ink.color), start, start + 1, 0)
                sb.append(ink.name).append(": ")
                sb.append(if (ink.coverage < 0.1) "%.2f".format(ink.coverage) else "%.1f".format(ink.coverage))
                sb.append("% заливки, по высоте ").append("${Math.round(ink.height)}%  ")
                sb.append(when { ink.unused -> "✗ не используется"; ink.ok -> "✓"; else -> "⚠ мало" })
                sb.append("\n")
            }
            label(sb, box, 14f).setLineSpacing(0f, 1.3f)
            val bad = inks.filter { !it.ok }
            label(
                (if (bad.isEmpty()) "Все 6 чернил задействованы по всей высоте карточки — дюзы прокачиваются. "
                else "Мало задействованы: ${bad.joinToString { it.name.lowercase() }}. Эти дюзы могут подсыхать. ") +
                    "«% заливки» — расход в пересчёте на сплошную заливку карточки. «По высоте» — на какой доле высоты " +
                    "встречается цвет: дюзы в головке стоят вертикальной колонкой, поэтому цвет должен идти по всей высоте. " +
                    "Точную раскладку по чернилам делает сам принтер, здесь — приблизительная оценка.",
                box, 12f, Ui.SUB
            ).setPadding(0, dp(6), 0, 0)

            android.app.AlertDialog.Builder(this@PrinterActivity)
                .setTitle("Предпросмотр печати")
                .setView(ScrollView(this@PrinterActivity).apply { addView(box) })
                .setPositiveButton("Напечатать") { _, _ -> runPrint() }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private enum class Maint { NOZZLE, CLEAN, TEST }

    private val sheetHint = "Выньте пачку с прогнозами из лотка и положите один чистый лист, " +
        "иначе принтер возьмёт лист из пачки. После — верните пачку на место."

    /** Проверка дюз, прочистка и тестовая страница — с подтверждением, потому что тратят бумагу и чернила. */
    private fun maintenance(kind: Maint) {
        saveConnection()
        if (!escpr && kind != Maint.TEST) {
            toast("Доступно для Epson с печатью ESC/P-R (порт 9100)")
            return
        }
        val (title, text) = when (kind) {
            Maint.NOZZLE -> "Проверка дюз" to
                "Принтер напечатает шаблон из линий каждого цвета. Если в линиях есть разрывы — нужна прочистка.\n\n$sheetHint"
            Maint.CLEAN -> "Прочистка головки" to
                "Прочистка расходует чернила и занимает 1–2 минуты — не выключайте принтер, пока мигает индикатор. " +
                "Потом сделайте проверку дюз.\n\nЕсли после 2–3 прочисток подряд разрывы остаются, " +
                "дайте принтеру постоять несколько часов и повторите."
            Maint.TEST -> "Тест качества печати" to
                "Принтер напечатает целый лист: заливки всеми чернилами, градиенты, шкалу серого, спектр, тонкие линии и мелкий текст. " +
                "Счётчик бумаги не меняется.\n\n$sheetHint"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(if (kind == Maint.CLEAN) "Начать" else "Печатать") { _, _ -> runMaintenance(kind) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runMaintenance(kind: Maint) {
        toast("Отправляю на принтер…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val lan = LocalNet.route(this@PrinterActivity)
                    val epson = EscprClient(prefs.printerHost, lan)
                    when (kind) {
                        Maint.NOZZLE -> epson.nozzleCheck()
                        Maint.CLEAN -> epson.headCleaning()
                        Maint.TEST ->
                            if (escpr) epson.printParts(TestPage.bands())
                            else IppClient(prefs.printerHost, prefs.ippPath, lan)
                                .printJob(TestPage.renderJpeg(), "image/jpeg", "Тест качества печати")
                    }
                }
            }
            toast(r.exceptionOrNull()?.let { "Не получилось: ${it.message}" } ?: when (kind) {
                Maint.NOZZLE -> "Шаблон проверки дюз отправлен"
                Maint.CLEAN -> "Прочистка запущена — подождите 1–2 минуты"
                Maint.TEST -> "Тестовая страница отправлена"
            })
        }
    }

    private fun findPrinter() {
        val dlg = android.app.ProgressDialog.show(this, null, "Ищу принтеры в сети… (до 15 секунд)", true)
        lifecycleScope.launch {
            val res = runCatching { PrinterDiscovery.discover(this@PrinterActivity) }
                .getOrElse { PrinterDiscovery.Result(emptyList(), "Ошибка поиска: ${it.message}") }
            val list = res.found
            dlg.dismiss()
            if (list.isEmpty()) {
                android.app.AlertDialog.Builder(this@PrinterActivity)
                    .setTitle("Принтер не найден")
                    .setMessage(
                        "Проверьте, что телефон подключён к домашнему Wi-Fi, а принтер включён и подключён к той же сети. " +
                            "Если не помогло — посмотрите IP принтера в списке устройств роутера и введите его вручную.\n\n" +
                            "Что удалось выяснить:\n" + res.report
                    )
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Скопировать") { _, _ ->
                        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("WeatherPrint", res.report))
                    }
                    .show()
                return@launch
            }
            val labels = list.map { "${it.model}\n${it.host}" }.toTypedArray()
            android.app.AlertDialog.Builder(this@PrinterActivity)
                .setTitle("Выберите принтер")
                .setItems(labels) { _, i ->
                    val f = list[i]
                    prefs.printerName = f.name
                    prefs.printerHost = f.host
                    prefs.ippPath = f.path
                    prefs.protocol = f.protocol
                    host.setText(f.host)
                    path.setText(f.path)
                    // Пересобираем экран: от способа печати зависит, доступно ли обслуживание
                    recreate()
                }
                .show()
        }
    }
}
