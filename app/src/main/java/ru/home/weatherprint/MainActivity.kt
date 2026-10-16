package ru.home.weatherprint

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Главный экран: погода, принтер, время печати и обновления. Всё остальное — на экранах принтера и настроек. */
class MainActivity : BaseActivity() {

    private lateinit var weatherView: WeatherView
    private var weatherKey = ""
    private var weatherLoadedAt = 0L

    private lateinit var reloadCard: LinearLayout
    private lateinit var reloadText: TextView
    private lateinit var updateCard: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateBtn: TextView
    private var pendingRelease: Updater.Release? = null
    private var updating = false

    private lateinit var printerArt: PrinterArt
    private lateinit var printerTitle: TextView
    private lateinit var printerStatus: TextView
    private lateinit var lastPrint: TextView

    private lateinit var scheduleSwitch: Switch
    private lateinit var printTime: TextView
    private lateinit var nextPrint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        screen("WeatherPrint", back = false, action = Icon.GEAR to {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        build()
        PrintJob.schedule(this)
        PrintSchedule.arm(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadWeather()
        checkPrinter()
        checkUpdate()
    }

    private fun build() {
        weatherView = WeatherView(this)
        content.addView(weatherView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })

        // Пора переложить бумагу — показывается только когда нужно
        reloadCard = card(color = Ui.WARN_SOFT)
        label("Пора переложить бумагу", reloadCard, 17f, Ui.WARN, bold = true)
        reloadText = label("", reloadCard, 15f).apply { setPadding(0, dp(6), 0, 0) }
        button("Бумага заложена", reloadCard) {
            prefs.awaitingReload = false
            toast("Продолжаем печать")
            refresh()
        }

        // Обновление — показывается, когда на GitHub есть новая версия
        updateCard = card(color = Ui.ACCENT_SOFT)
        val uRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        uRow.addView(iconBadge(Icon.DOWNLOAD, Color.WHITE, Ui.ACCENT, 40))
        updateText = TextView(this).apply {
            textSize = 15f; setTextColor(Ui.TEXT); setPadding(dp(14), 0, 0, 0); maxLines = 8
        }
        uRow.addView(updateText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updateCard.addView(uRow)
        updateBtn = button("Обновить", updateCard) { startUpdate() }
        updateCard.visibility = View.GONE

        // Принтер: нажатие открывает экран принтера
        val pc = card()
        pc.background = Ui.ripple(Ui.rounded(Ui.CARD, dpf(20f)), dpf(20f))
        pc.setOnClickListener { startActivity(Intent(this, PrinterActivity::class.java)) }
        val pRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        printerArt = PrinterArt(this).apply { background = Ui.rounded(Ui.BG, dpf(14f)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        pRow.addView(printerArt, LinearLayout.LayoutParams(dp(72), dp(56)))
        val pCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        printerTitle = TextView(this).apply {
            textSize = 17f; setTextColor(Ui.TEXT); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        printerStatus = TextView(this).apply { textSize = 14f; setTextColor(Ui.SUB); setPadding(0, dp(2), 0, 0) }
        pCol.addView(printerTitle); pCol.addView(printerStatus)
        pRow.addView(pCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pRow.addView(IconView(this).apply { icon = Icon.CHEVRON; color = Ui.OFF }, LinearLayout.LayoutParams(dp(22), dp(22)))
        pc.addView(pRow)
        lastPrint = label("", pc, 13f, Ui.SUB).apply { setPadding(dp(86), dp(10), 0, 0) }

        // Печать по расписанию
        val sc = card()
        val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sRow.addView(iconBadge(Icon.CLOCK, Ui.ACCENT, Ui.ACCENT_SOFT, 48))
        val sCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        sCol.addView(TextView(this).apply {
            text = "Печать по расписанию"; textSize = 17f; setTextColor(Ui.TEXT)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        })
        nextPrint = TextView(this).apply { textSize = 14f; setTextColor(Ui.SUB); setPadding(0, dp(2), 0, 0) }
        sCol.addView(nextPrint)
        sRow.addView(sCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scheduleSwitch = Switch(this).apply {
            isChecked = prefs.scheduleEnabled
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Color.WHITE)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Ui.OFF)
            )
            setOnCheckedChangeListener { _, on ->
                prefs.scheduleEnabled = on
                PrintSchedule.arm(this@MainActivity)
                refresh()
            }
        }
        sRow.addView(scheduleSwitch)
        sc.addView(sRow)
        printTime = TextView(this).apply {
            textSize = 44f
            setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            background = Ui.ripple(Ui.rounded(Ui.BG, dpf(16f)), dpf(16f))
            setOnClickListener { pickTime() }
        }
        sc.addView(printTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) })
        label("Нажмите на время, чтобы изменить", sc, 12f, Ui.SUB).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }
    }

    private fun refresh() {
        // Расписание
        printTime.text = PrintSchedule.format(prefs.printMinutes)
        printTime.alpha = if (prefs.scheduleEnabled) 1f else 0.35f
        if (scheduleSwitch.isChecked != prefs.scheduleEnabled) scheduleSwitch.isChecked = prefs.scheduleEnabled
        nextPrint.text = if (prefs.scheduleEnabled) "Следующая: ${PrintSchedule.describe(this)}" else "Выключено"

        // Принтер
        printerTitle.text = prefs.printerModel.ifEmpty { prefs.printerName }.ifEmpty { "Принтер не выбран" }
        printerArt.showFor(this, prefs)
        if (prefs.printerName.isEmpty()) printerStatus.text = "Нажмите, чтобы найти принтер в сети"
        lastPrint.text = "Последняя печать: ${prefs.lastResult}"

        // Бумага
        reloadCard.visibility = if (prefs.awaitingReload) View.VISIBLE else View.GONE
        if (prefs.awaitingReload) reloadText.text = PaperText.reloadInstruction(prefs)
    }

    private fun pickTime() {
        val m = prefs.printMinutes
        android.app.TimePickerDialog(this, { _, h, min ->
            prefs.printMinutes = h * 60 + min
            prefs.scheduleEnabled = true
            PrintSchedule.arm(this)
            refresh()
        }, m / 60, m % 60, true).show()
    }

    /** Проверяет, отвечает ли принтер, — для строки состояния на карточке. Заодно один раз определяет модель и картинку. */
    private fun checkPrinter() {
        if (prefs.printerName.isEmpty()) return
        printerStatus.text = statusText(null, "Проверяю связь…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val lan = LocalNet.route(this@MainActivity)
                    if (prefs.protocol == "escpr") EscprClient(prefs.printerHost, lan).ping()
                    else IppClient(prefs.printerHost, prefs.ippPath, lan).getAttributes(timeoutMs = 3000)
                }.isSuccess
            }
            printerStatus.text = statusText(ok, if (ok) "В сети · ${prefs.printerHost}" else "Не в сети · ${prefs.printerHost}")
            if (ok && prefs.printerModel.isEmpty()) {
                withContext(Dispatchers.IO) { PrinterInfo.identify(this@MainActivity) }
                refresh()
            }
        }
    }

    /** Погода — не чаще раза в 10 минут или сразу, если в настройках сменили город или координаты. */
    private fun loadWeather() {
        val key = "${prefs.city}|${prefs.lat}|${prefs.lon}"
        weatherView.city = prefs.city
        val fresh = System.currentTimeMillis() - weatherLoadedAt < 10 * 60_000
        if (key == weatherKey && fresh && weatherView.weather != null) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { WeatherApi.fetch(prefs.lat, prefs.lon) } }
            r.onSuccess {
                weatherView.weather = it
                weatherLoadedAt = System.currentTimeMillis()
                weatherKey = key
            }.onFailure {
                if (weatherView.weather == null) weatherView.message = "Нет связи с сервисом погоды"
            }
        }
    }

    private fun checkUpdate() {
        if (updating) return
        lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { runCatching { Updater.checkForUpdate(this@MainActivity) }.getOrNull() }
            pendingRelease = rel
            if (rel == null) {
                updateCard.visibility = View.GONE
                return@launch
            }
            updateText.text = android.text.SpannableStringBuilder().apply {
                val s = length
                append("Доступна версия ${rel.name}")
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                if (rel.notes.isNotBlank()) append("\n").append(rel.notes)
            }
            updateBtn.text = "Обновить до ${rel.name}"
            updateCard.visibility = View.VISIBLE
        }
    }

    private fun startUpdate() {
        val rel = pendingRelease ?: return
        if (!Updater.ensureInstallPermission(this)) {
            toast("Разрешите установку обновлений и нажмите «Обновить» ещё раз")
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
                toast("Ошибка обновления: ${it.message}")
                updateBtn.text = "Обновить до ${rel.name}"
                updateBtn.isEnabled = true
            }
        }
    }
}

/** Тексты про бумагу — общие для главного экрана и настроек. */
object PaperText {
    fun reloadInstruction(p: Prefs): String = when (p.pass) {
        0 -> "Пачка заполнена полностью. Выньте её и заложите ${p.sheets} новых листов, затем нажмите «Бумага заложена»."
        4 -> "Лицевая сторона заполнена.\n1. Достаньте все листы из выходного лотка.\n" +
            "2. Переверните пачку, как страницу книги, чистой стороной к себе.\n3. Положите в задний лоток и нажмите «Бумага заложена»."
        else -> "Проход ${p.pass} завершён.\n1. Достаньте все листы из выходного лотка.\n" +
            "2. Положите обратно в задний лоток той же стороной, ничего не поворачивая.\n" +
            "3. Нажмите «Бумага заложена». Следующие прогнозы лягут в четверть №${p.pass + 1}."
    }

    fun status(p: Prefs): String {
        val side = if (p.backSide) "оборот" else "лицевая сторона"
        return "Проход ${p.pass + 1} из 8 ($side), лист ${p.sheetIndex + 1} из ${p.sheets}.\n" +
            "Бумаги хватит примерно на ${p.daysLeft} дн."
    }
}
