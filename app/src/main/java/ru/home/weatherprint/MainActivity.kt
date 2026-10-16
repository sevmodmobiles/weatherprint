package ru.home.weatherprint

import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var reloadBtn: Button
    private lateinit var sheet: SheetView
    private lateinit var printerLabel: TextView
    private lateinit var weatherView: WeatherView
    private var weatherLoadedAt = 0L
    private lateinit var updateBox: TextView
    private lateinit var updateBtn: Button
    private var pendingRelease: Updater.Release? = null
    private var updating = false

    private lateinit var host: EditText
    private lateinit var path: EditText
    private lateinit var city: EditText
    private lateinit var lat: EditText
    private lateinit var lon: EditText
    private lateinit var sheets: EditText
    private lateinit var scheduleOn: android.widget.CheckBox
    private lateinit var printTime: TextView
    private lateinit var nextPrint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContentView(buildUi())
        PrintJob.schedule(this)
        PrintSchedule.arm(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            refresh()
            loadWeather()
            checkUpdate(silent = true)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(16), 0, dp(6))
        }.also { root.addView(it) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }.also { root.addView(it) }
        fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            root.addView(TextView(this).apply { text = label; setTextColor(Color.DKGRAY) })
            return EditText(this).apply { setText(value); inputType = type; isSingleLine = true }
                .also { root.addView(it) }
        }

        weatherView = WeatherView(this).apply { city = prefs.city }
        root.addView(weatherView)

        root.addView(TextView(this).apply {
            text = "WeatherPrint · версия ${Updater.currentVersionName(this@MainActivity)}"
            setTextColor(Color.GRAY); setPadding(dp(4), dp(8), 0, dp(4))
        })

        updateBox = TextView(this).apply {
            textSize = 15f; setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(214, 238, 255)); visibility = View.GONE
        }
        root.addView(updateBox)
        updateBtn = button("Обновить") { startUpdate() }.apply { visibility = View.GONE }

        instruction = TextView(this).apply {
            textSize = 16f; setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(255, 243, 205))
        }
        root.addView(instruction)

        reloadBtn = button("Бумага заложена") {
            prefs.awaitingReload = false
            Toast.makeText(this, "Продолжаем печать", Toast.LENGTH_SHORT).show()
            refresh()
        }

        button("Напечатать сейчас") { runPrint() }
        button("Предпросмотр печати") { showPreview() }
        button("Проверить принтер") { checkPrinter() }

        header("Бумага")
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        root.addView(status)
        sheet = SheetView(this)
        root.addView(sheet)

        header("Печать по расписанию")
        scheduleOn = android.widget.CheckBox(this).apply {
            text = "Печатать каждый день автоматически"; textSize = 16f
            isChecked = prefs.scheduleEnabled
            setOnCheckedChangeListener { _, on ->
                prefs.scheduleEnabled = on
                PrintSchedule.arm(this@MainActivity)
                refresh()
            }
        }
        root.addView(scheduleOn)
        printTime = TextView(this).apply {
            textSize = 34f; setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(4), 0, 0)
            setTextColor(Color.rgb(0, 110, 200))
            setOnClickListener { pickTime() }
        }
        root.addView(printTime)
        nextPrint = TextView(this).apply { textSize = 14f; setTextColor(Color.GRAY); setPadding(dp(4), 0, 0, dp(4)) }
        root.addView(nextPrint)
        button("Изменить время печати") { pickTime() }

        header("Принтер")
        printerLabel = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(4)) }
        root.addView(printerLabel)
        button("Найти принтер в сети") { findPrinter() }

        header("Обслуживание принтера")
        button("Проверка дюз") { maintenance(Maint.NOZZLE) }
        button("Прочистка печатающей головки") { maintenance(Maint.CLEAN) }
        button("Тест качества печати") { maintenance(Maint.TEST) }

        header("Настройки")
        host = field("IP-адрес принтера (заполняется сам при поиске)", prefs.printerHost)
        path = field("Путь IPP (обычно /ipp/print)", prefs.ippPath)
        city = field("Город (для заголовка)", prefs.city)
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        lat = field("Широта", prefs.lat.toString(), num)
        lon = field("Долгота", prefs.lon.toString(), num)
        sheets = field("Листов в пачке", prefs.sheets.toString(), InputType.TYPE_CLASS_NUMBER)
        button("Сохранить настройки") { save() }

        header("Обновления")
        button("Проверить обновления") { checkUpdate(silent = false) }

        header("Счётчик")
        button("Новая пачка: сбросить счётчик") {
            prefs.pass = 0; prefs.sheetIndex = 0; prefs.awaitingReload = false
            refresh()
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun save() {
        prefs.printerHost = host.text.toString()
        prefs.ippPath = path.text.toString()
        prefs.city = city.text.toString()
        lat.text.toString().replace(',', '.').toDoubleOrNull()?.let { prefs.lat = it }
        lon.text.toString().replace(',', '.').toDoubleOrNull()?.let { prefs.lon = it }
        sheets.text.toString().toIntOrNull()?.let { prefs.sheets = it }
        PrintJob.schedule(this)
        weatherView.city = prefs.city
        weatherLoadedAt = 0L
        loadWeather()
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun pickTime() {
        val m = prefs.printMinutes
        android.app.TimePickerDialog(this, { _, h, min ->
            prefs.printMinutes = h * 60 + min
            prefs.scheduleEnabled = true
            scheduleOn.isChecked = true
            PrintSchedule.arm(this)
            refresh()
        }, m / 60, m % 60, true).show()
    }

    private fun refresh() {
        printTime.text = PrintSchedule.format(prefs.printMinutes)
        printTime.alpha = if (prefs.scheduleEnabled) 1f else 0.4f
        nextPrint.text = if (prefs.scheduleEnabled)
            "Следующая печать: ${PrintSchedule.describe(this)}. Если принтер в это время недоступен, " +
                "приложение повторит попытку позже в тот же день."
        else "Расписание выключено — печать только кнопкой «Напечатать сейчас»"
        printerLabel.text = if (prefs.printerName.isNotEmpty())
            "Выбран: ${prefs.printerName} (${prefs.printerHost})"
        else "Принтер не выбран — нажмите «Найти принтер в сети»"
        val side = if (prefs.backSide) "оборот" else "лицевая сторона"
        status.text = "Проход ${prefs.pass + 1} из 8 ($side), лист ${prefs.sheetIndex + 1} из ${prefs.sheets}\n" +
            "Хватит ещё примерно на ${prefs.daysLeft} дн.\n" +
            "Последнее: ${prefs.lastResult}"
        sheet.pass = prefs.pass
        if (prefs.awaitingReload) {
            instruction.visibility = View.VISIBLE
            reloadBtn.visibility = View.VISIBLE
            instruction.text = when (prefs.pass) {
                0 -> "Пачка заполнена полностью. Выньте её и заложите ${prefs.sheets} новых листов, затем нажмите «Бумага заложена»."
                4 -> "Лицевая сторона заполнена.\n1. Достаньте все листы из выходного лотка.\n2. Переверните пачку, как страницу книги, чистой стороной к себе.\n3. Положите в задний лоток и нажмите «Бумага заложена»."
                else -> "Проход ${prefs.pass} завершён.\n1. Достаньте все листы из выходного лотка.\n2. Положите обратно в задний лоток той же стороной, ничего не поворачивая.\n3. Нажмите «Бумага заложена». Следующие прогнозы лягут в четверть №${prefs.pass + 1}."
            }
        } else {
            instruction.visibility = View.GONE
            reloadBtn.visibility = View.GONE
        }
    }

    /** Показывает, как выйдет прогноз, и примерный расход каждого из 6 чернил — без бумаги и чернил. */
    private fun showPreview() {
        save()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val wx = weatherView.weather ?: withContext(Dispatchers.IO) { WeatherApi.fetch(prefs.lat, prefs.lon) }
                    val pv = PageRenderer.renderPreview(wx, prefs.city, PrintJob.footerText(prefs))
                    pv to InkCheck.analyze(pv.bitmap, pv.contentTop, pv.contentBottom)
                }
            }
            val (pv, inks) = r.getOrElse {
                Toast.makeText(this@MainActivity, "Не удалось получить погоду: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            box.addView(android.widget.ImageView(this@MainActivity).apply {
                setImageBitmap(pv.bitmap)
                adjustViewBounds = true
                setBackgroundColor(Color.rgb(200, 200, 200))
                setPadding(dp(1), dp(1), dp(1), dp(1))
            })
            box.addView(TextView(this@MainActivity).apply {
                text = "Так прогноз выйдет на четверти листа (${PrintJob.footerText(prefs)})."
                setTextColor(Color.GRAY); setPadding(0, dp(6), 0, dp(10))
            })
            box.addView(TextView(this@MainActivity).apply {
                text = "Чернила (оценка)"; textSize = 16f; setTypeface(null, Typeface.BOLD)
            })
            val sb = android.text.SpannableStringBuilder()
            for (ink in inks) {
                val start = sb.length
                sb.append("■ ")
                sb.setSpan(android.text.style.ForegroundColorSpan(ink.color), start, start + 1, 0)
                sb.append(ink.name).append(": ")
                sb.append(if (ink.coverage < 0.1) "%.2f".format(ink.coverage) else "%.1f".format(ink.coverage))
                sb.append("% заливки, по высоте ").append("${Math.round(ink.height)}%  ")
                sb.append(when { ink.unused -> "✗ не используется"; ink.ok -> "✓"; else -> "⚠ мало" })
                sb.append("\n")
            }
            box.addView(TextView(this@MainActivity).apply { text = sb; textSize = 14f; setLineSpacing(0f, 1.2f) })
            val bad = inks.filter { !it.ok }
            box.addView(TextView(this@MainActivity).apply {
                setTextColor(Color.GRAY); textSize = 12f; setPadding(0, dp(6), 0, 0)
                text = (if (bad.isEmpty()) "Все 6 чернил задействованы по всей высоте карточки — дюзы прокачиваются. "
                else "Мало задействованы: ${bad.joinToString { it.name.lowercase() }}. Эти дюзы могут подсыхать. ") +
                    "«% заливки» — расход в пересчёте на сплошную заливку карточки. «По высоте» — на какой доле высоты " +
                    "встречается цвет: дюзы в головке стоят вертикальной колонкой, поэтому цвет должен идти по всей высоте. " +
                    "Точную раскладку по чернилам делает сам принтер, здесь — приблизительная оценка."
            })

            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Предпросмотр печати")
                .setView(ScrollView(this@MainActivity).apply { addView(box) })
                .setPositiveButton("Напечатать") { _, _ -> runPrint() }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun runPrint() {
        save()
        lifecycleScope.launch {
            val (_, msg) = withContext(Dispatchers.IO) { PrintJob.run(this@MainActivity, force = true) }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun checkPrinter() {
        save()
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val lan = LocalNet.route(this@MainActivity)
                try {
                    if (prefs.protocol == "escpr") {
                        EscprClient(prefs.printerHost, lan).ping()
                        "Принтер Epson на ${prefs.printerHost} отвечает ✓\nПечать через порт 9100 (ESC/P-R)"
                    } else {
                        val a = IppClient(prefs.printerHost, prefs.ippPath, lan).getAttributes()
                        val model = a["printer-make-and-model"]?.firstOrNull() ?: "?"
                        val formats = a["document-format-supported"].orEmpty()
                        val jpeg = if ("image/jpeg" in formats) "JPEG поддерживается ✓" else "JPEG НЕ найден в списке форматов"
                        "Принтер: $model\n$jpeg\nФорматы: ${formats.joinToString()}"
                    }
                } catch (e: Exception) {
                    "Принтер не отвечает: ${e.message}"
                }
            }
            status.text = msg
        }
    }

    private enum class Maint { NOZZLE, CLEAN, TEST }

    private val sheetHint = "Выньте пачку с прогнозами из лотка и положите один чистый лист, " +
        "иначе принтер возьмёт лист из пачки. После — верните пачку на место."

    /** Проверка дюз, прочистка и тестовая страница — с подтверждением, потому что тратят бумагу и чернила. */
    private fun maintenance(kind: Maint) {
        save()
        val escpr = prefs.protocol == "escpr"
        if (!escpr && kind != Maint.TEST) {
            Toast.makeText(this, "Доступно для Epson, найденного как «печать Epson ESC/P-R»", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "Отправляю на принтер…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val lan = LocalNet.route(this@MainActivity)
                    val epson = EscprClient(prefs.printerHost, lan)
                    when (kind) {
                        Maint.NOZZLE -> epson.nozzleCheck()
                        Maint.CLEAN -> epson.headCleaning()
                        Maint.TEST ->
                            if (prefs.protocol == "escpr") epson.printParts(TestPage.bands())
                            else IppClient(prefs.printerHost, prefs.ippPath, lan)
                                .printJob(TestPage.renderJpeg(), "image/jpeg", "Тест качества печати")
                    }
                }
            }
            val msg = r.exceptionOrNull()?.let { "Не получилось: ${it.message}" } ?: when (kind) {
                Maint.NOZZLE -> "Шаблон проверки дюз отправлен"
                Maint.CLEAN -> "Прочистка запущена — подождите 1–2 минуты"
                Maint.TEST -> "Тестовая страница отправлена"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkUpdate(silent: Boolean) {
        if (updating) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.checkForUpdate(this@MainActivity) } }
            val rel = result.getOrNull()
            pendingRelease = rel
            if (rel != null) {
                updateBox.text = "Доступна версия ${rel.name}" +
                    (if (rel.notes.isNotBlank()) "\n\nЧто нового:\n${rel.notes}" else "")
                updateBox.visibility = View.VISIBLE
                updateBtn.text = "Обновить до ${rel.name}"
                updateBtn.visibility = View.VISIBLE
                updateBtn.isEnabled = true
            } else {
                updateBox.visibility = View.GONE
                updateBtn.visibility = View.GONE
                if (!silent) {
                    val msg = result.exceptionOrNull()?.let { "Не удалось проверить: ${it.message}" }
                        ?: "У вас последняя версия"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startUpdate() {
        val rel = pendingRelease ?: return
        if (!Updater.ensureInstallPermission(this)) {
            Toast.makeText(this, "Разрешите установку обновлений и нажмите «Обновить» ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        updating = true
        updateBtn.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = Updater.download(this@MainActivity, rel) { pct ->
                        runOnUiThread { updateBtn.text = "Скачивание… $pct%" }
                    }
                    runOnUiThread { updateBtn.text = "Установка…" }
                    Updater.install(this@MainActivity, apk)
                }
            }
            updating = false
            result.exceptionOrNull()?.let {
                Toast.makeText(this@MainActivity, "Ошибка обновления: ${it.message}", Toast.LENGTH_LONG).show()
                updateBtn.text = "Обновить до ${rel.name}"
                updateBtn.isEnabled = true
            }
        }
    }

    private fun findPrinter() {
        val dlg = android.app.ProgressDialog.show(this, null, "Ищу принтеры в сети… (до 15 секунд)", true)
        lifecycleScope.launch {
            val res = runCatching { PrinterDiscovery.discover(this@MainActivity) }
                .getOrElse { PrinterDiscovery.Result(emptyList(), "Ошибка поиска: ${it.message}") }
            val list = res.found
            dlg.dismiss()
            if (list.isEmpty()) {
                android.app.AlertDialog.Builder(this@MainActivity)
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
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Выберите принтер")
                .setItems(labels) { _, i ->
                    val f = list[i]
                    prefs.printerName = f.name
                    prefs.printerHost = f.host
                    prefs.ippPath = f.path
                    prefs.protocol = f.protocol
                    host.setText(f.host)
                    path.setText(f.path)
                    refresh()
                    checkPrinter()
                }
                .show()
        }
    }

    /** Обновляет карточку погоды не чаще раза в 10 минут. */
    private fun loadWeather() {
        if (System.currentTimeMillis() - weatherLoadedAt < 10 * 60_000 && weatherView.weather != null) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { WeatherApi.fetch(prefs.lat, prefs.lon) } }
            r.onSuccess {
                weatherView.weather = it
                weatherLoadedAt = System.currentTimeMillis()
            }.onFailure {
                if (weatherView.weather == null) weatherView.message = "Нет связи с сервисом погоды"
            }
        }
    }
}
