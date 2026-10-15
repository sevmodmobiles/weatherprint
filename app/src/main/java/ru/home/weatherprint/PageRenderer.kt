package ru.home.weatherprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Рисует лист A4 (300 dpi), где заполнена только одна четверть.
 * Внизу прогноза — полоса из 6 цветов L805, чтобы прокачивались все дюзы,
 * включая светло-голубую и светло-пурпурную.
 */
object PageRenderer {
    private const val W = 2480
    private const val H = 3508
    private const val PAD = 120f // ≈1 см от края четверти — с запасом на поля принтера

    private val ru = Locale("ru")

    fun renderJpeg(w: Weather, quadrant: Int, city: String, footer: String): ByteArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val qw = W / 2f
        val qh = H / 2f
        val left = (quadrant % 2) * qw
        val top = (quadrant / 2) * qh
        c.save()
        c.translate(left + PAD, top + PAD)
        drawForecast(c, w, city, footer, qw - 2 * PAD, qh - 2 * PAD)
        c.restore()

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun drawForecast(c: Canvas, w: Weather, city: String, footer: String, cw: Float, ch: Float) {
        val gray = Color.rgb(90, 90, 90)
        val line = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = 4f }

        // Шапка: дата и город
        val today = LocalDate.now()
        val dateText = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru))
            .replaceFirstChar { it.uppercase(ru) }
        c.drawText(dateText, 0f, 70f, paint(66f, bold = true))
        c.drawText(city, 0f, 140f, paint(46f, color = gray))
        c.drawLine(0f, 180f, cw, 180f, line)

        // Сейчас: большая температура, цвет — синий для мороза, оранжевый для тепла
        val tempColor = if (w.temp < 0) Color.rgb(0, 110, 200) else Color.rgb(230, 100, 0)
        val big = paint(250f, bold = true, color = tempColor)
        val tempText = WeatherApi.fmtTemp(w.temp)
        c.drawText(tempText, 0f, 470f, big)
        val tx = big.measureText(tempText) + 40f
        val curPaint = paint(54f, bold = true)
        c.drawText(ellipsize(WeatherApi.describe(w.code), curPaint, cw - tx), tx, 300f, curPaint)
        c.drawText("ощущается ${WeatherApi.fmtTemp(w.feels)}", tx, 375f, paint(42f, color = gray))
        c.drawText("ветер ${Math.round(w.wind)} м/с", tx, 445f, paint(42f, color = gray))

        // Сегодня целиком
        w.days.firstOrNull()?.let { d ->
            c.drawText(
                "Сегодня ${WeatherApi.fmtTemp(d.tMin)}…${WeatherApi.fmtTemp(d.tMax)}, осадки ${d.precipProb}%",
                0f, 570f, paint(48f)
            )
        }
        c.drawLine(0f, 620f, cw, 620f, line)

        // Следующие 3 дня
        val dayFmt = DateTimeFormatter.ofPattern("EE d", ru)
        var y = 720f
        for (d in w.days.drop(1).take(3)) {
            c.drawText(d.date.format(dayFmt), 0f, y, paint(48f, bold = true))
            c.drawText("${WeatherApi.fmtTemp(d.tMin)}…${WeatherApi.fmtTemp(d.tMax)}", 210f, y, paint(48f))
            val desc = WeatherApi.describe(d.code)
            val descPaint = paint(38f, color = gray)
            c.drawText(ellipsize(desc, descPaint, cw - 480f - 150f), 480f, y, descPaint)
            val pr = "${d.precipProb}%"
            val prPaint = paint(44f, color = Color.rgb(0, 110, 200))
            c.drawText(pr, cw - prPaint.measureText(pr), y, prPaint)
            y += 115f
        }

        // Полоса прокачки: C, M, Y, K, светло-голубой, светло-пурпурный
        val inks = intArrayOf(
            Color.rgb(0, 255, 255),
            Color.rgb(255, 0, 255),
            Color.rgb(255, 255, 0),
            Color.rgb(0, 0, 0),
            Color.rgb(170, 235, 255),
            Color.rgb(255, 185, 240),
        )
        val stripTop = ch - 230f
        val stripH = 90f
        val gap = 16f
        val sw = (cw - gap * (inks.size - 1)) / inks.size
        val box = Paint()
        inks.forEachIndexed { i, col ->
            box.color = col
            val x = i * (sw + gap)
            c.drawRect(x, stripTop, x + sw, stripTop + stripH, box)
        }

        // Подвал: счётчик листа
        c.drawText(footer, 0f, ch - 40f, paint(36f, color = gray))
    }

    private fun ellipsize(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
