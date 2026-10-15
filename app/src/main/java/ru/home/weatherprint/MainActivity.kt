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

    private lateinit var host: EditText
    private lateinit var path: EditText
    private lateinit var city: EditText
    private lateinit var lat: EditText
    private lateinit var lon: EditText
    private lateinit var sheets: EditText
    private lateinit var fromHour: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContentView(buildUi())
        PrintJob.schedule(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
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

        root.addView(TextView(this).apply {
            text = "Погода на принтер"; textSize = 24f; setTypeface(null, Typeface.BOLD)
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        sheet = SheetView(this)
        root.addView(sheet)

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
        button("Проверить принтер") { checkPrinter() }

        header("Настройки")
        host = field("IP-адрес принтера", prefs.printerHost)
        path = field("Путь IPP (обычно /ipp/print)", prefs.ippPath)
        city = field("Город (для заголовка)", prefs.city)
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        lat = field("Широта", prefs.lat.toString(), num)
        lon = field("Долгота", prefs.lon.toString(), num)
        sheets = field("Листов в пачке", prefs.sheets.toString(), InputType.TYPE_CLASS_NUMBER)
        fromHour = field("Печатать не раньше, часов", prefs.fromHour.toString(), InputType.TYPE_CLASS_NUMBER)
        button("Сохранить настройки") { save() }

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
        fromHour.text.toString().toIntOrNull()?.let { prefs.fromHour = it }
        PrintJob.schedule(this)
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun refresh() {
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
                try {
                    val a = IppClient(prefs.printerHost, prefs.ippPath).getAttributes()
                    val model = a["printer-make-and-model"]?.firstOrNull() ?: "?"
                    val formats = a["document-format-supported"].orEmpty()
                    val jpeg = if ("image/jpeg" in formats) "JPEG поддерживается ✓" else "JPEG НЕ найден в списке форматов"
                    "Принтер: $model\n$jpeg\nФорматы: ${formats.joinToString()}"
                } catch (e: Exception) {
                    "Принтер не отвечает: ${e.message}"
                }
            }
            status.text = msg
        }
    }
}
