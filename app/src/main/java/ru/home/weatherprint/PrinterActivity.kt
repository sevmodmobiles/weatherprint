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

    private lateinit var art: PrinterArt
    private lateinit var nameText: TextView
    private lateinit var infoText: TextView
    private lateinit var statusLine: TextView
    private lateinit var detailsText: TextView
    private lateinit var host: EditText
    private lateinit var path: EditText
    private lateinit var pathBox: LinearLayout
    private lateinit var cleanSub: TextView
    private var cleaningRunning = false

    private val escpr get() = prefs.protocol == "escpr"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Принтер", back = true)
        build()
        refresh()
        checkPrinter()
        watchCleaning()
    }

    override fun onPause() {
        super.onPause()
        saveConnection()
    }

    private fun build() {
        // Шапка: принтер и его состояние
        val head = card(pad = 20)
        art = PrinterArt(this).apply { background = Ui.rounded(Ui.BG, dpf(16f)); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        head.addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        nameText = TextView(this).apply {
            textSize = 22f; setTextColor(Ui.TEXT); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
        }
        infoText = TextView(this).apply { textSize = 14f; setTextColor(Ui.SUB); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }
        statusLine = TextView(this).apply { textSize = 14f; setTextColor(Ui.SUB); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        head.addView(nameText); head.addView(infoText); head.addView(statusLine)
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
        cleanSub = listItem(m, Icon.DROP, "Прочистка печатающей головки", hint ?: "Можно несколько раз подряд", enabled = escpr) {
            when {
                !escpr -> toast("Доступно для Epson с печатью ESC/P-R (порт 9100)")
                cleaningRunning -> confirmStopCleaning()
                else -> cleaningDialog()
            }
        }
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
        nameText.text = prefs.printerModel.ifEmpty { prefs.printerName }.ifEmpty { "Принтер не выбран" }
        art.showFor(this, prefs)
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
            if (ok && prefs.printerName.isNotEmpty() && (details || prefs.printerModel.isEmpty())) {
                withContext(Dispatchers.IO) { PrinterInfo.identify(this@PrinterActivity) }
                refresh()
            }
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

    /** Диалог прочистки: сколько раз подряд и печатать ли потом проверку дюз. */
    private fun cleaningDialog() {
        saveConnection()
        var times = 2
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        label("Прочистка расходует чернила и занимает 1–2 минуты. Не выключайте принтер, пока она идёт — " +
            "приложение само дождётся окончания каждой прочистки и сообщит, когда всё закончится.", box, 14f, Ui.SUB)
        label("Сколько раз подряд", box, 15f, bold = true).setPadding(0, dp(16), 0, dp(8))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val warn = label("", box, 12f, Ui.WARN)
        box.removeView(warn)
        val views = (1..5).map { n ->
            TextView(this).apply {
                text = "$n"; textSize = 17f; gravity = Gravity.CENTER
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            }
        }
        fun paint() {
            views.forEachIndexed { i, v ->
                val on = i + 1 == times
                v.setTextColor(if (on) Color.WHITE else Ui.ACCENT)
                v.background = Ui.rounded(if (on) Ui.ACCENT else Ui.ACCENT_SOFT, dpf(22f))
            }
            warn.text = if (times > 3) "Epson не советует больше 2–3 прочисток подряд: если не помогает, " +
                "лучше дать принтеру постоять несколько часов и повторить." else ""
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener { times = i + 1; paint() }
            chips.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        box.addView(chips)
        box.addView(warn)
        warn.setPadding(0, dp(8), 0, 0)
        val check = android.widget.CheckBox(this).apply {
            text = "После прочистки напечатать проверку дюз"; isChecked = true; textSize = 15f
            setPadding(dp(4), dp(12), 0, dp(12))
        }
        box.addView(check, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        label("Для проверки дюз положите в лоток отдельный чистый лист.", box, 12f, Ui.SUB)
        paint()

        android.app.AlertDialog.Builder(this)
            .setTitle("Прочистка головки")
            .setView(box)
            .setPositiveButton("Начать") { _, _ ->
                CleaningWorker.start(this, times, check.isChecked)
                toast(if (times == 1) "Прочистка запущена" else "Запущено $times прочистки подряд")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmStopCleaning() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Идёт прочистка")
            .setMessage("Остановить серию? Текущую прочистку принтер закончит сам, следующие запущены не будут.")
            .setPositiveButton("Остановить") { _, _ -> CleaningWorker.cancel(this) }
            .setNegativeButton("Продолжить", null)
            .show()
    }

    /** Показывает в строке «Прочистка» ход серии, пока она идёт. */
    private fun watchCleaning() {
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(CleaningWorker.WORK)
            .observe(this) { infos ->
                val running = infos.firstOrNull { !it.state.isFinished }
                cleaningRunning = running != null
                cleanSub.text = when {
                    !escpr -> "Только для Epson с печатью ESC/P-R"
                    running != null -> (running.progress.getString("title") ?: "Идёт прочистка") + " · нажмите, чтобы остановить"
                    else -> "Можно несколько раз подряд"
                }
                cleanSub.setTextColor(if (running != null) Ui.ACCENT else Ui.SUB)
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
            val labels = list.map {
                val how = if (it.protocol == "escpr") "Epson ESC/P-R" else "AirPrint / IPP"
                "${PrinterInfo.cleanModel(it.model)}\n${it.host} · $how"
            }.toTypedArray()
            android.app.AlertDialog.Builder(this@PrinterActivity)
                .setTitle("Выберите принтер")
                .setItems(labels) { _, i ->
                    val f = list[i]
                    prefs.printerName = f.name
                    prefs.printerHost = f.host
                    prefs.ippPath = f.path
                    prefs.protocol = f.protocol
                    prefs.printerModel = PrinterInfo.cleanModel(f.model)
                    host.setText(f.host)
                    path.setText(f.path)
                    lifecycleScope.launch {
                        toast("Определяю модель принтера…")
                        withContext(Dispatchers.IO) { PrinterInfo.identify(this@PrinterActivity) }
                        // Пересобираем экран: от способа печати зависит, доступно ли обслуживание
                        recreate()
                    }
                }
                .show()
        }
    }
}
