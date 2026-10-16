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
 * Слева у прогноза — «цветной корешок»: 6 тонких вертикальных линий цветами чернил L805
 * во всю высоту карточки. Колонки дюз в головке вертикальные (≈1,5–2 см), поэтому линия
 * во всю высоту прогоняет каждую дюзу своего цвета, а чернил тратит совсем немного.
 */
object PageRenderer {
    private const val W = 2480
    private const val H = 3508
    private const val PAD = 120f // ≈1 см от края четверти — с запасом на поля принтера

    private val ru = Locale("ru")

    /** Чернила L805 и цвета, которыми их задействуем: C, M, Y, K, светло-голубой, светло-пурпурный. */
    val INKS = listOf(
        "Голубой" to Color.rgb(0, 255, 255),
        "Пурпурный" to Color.rgb(255, 0, 255),
        "Жёлтый" to Color.rgb(255, 255, 0),
        "Чёрный" to Color.rgb(0, 0, 0),
        "Светло-голубой" to Color.rgb(170, 235, 255),
        "Светло-пурпурный" to Color.rgb(255, 185, 240),
    )

    /** Корешок: толщина линии, промежуток и отступ до текста (единицы 300 dpi; 12 ≈ 1 мм). */
    private const val SPINE_LINE = 9f
    private const val SPINE_GAP = 5f
    private const val SPINE_TEXT_GAP = 40f

    /** Предпросмотр четверти для экрана: [contentTop]–[contentBottom] — строки, где идёт прогноз. */
    class Preview(val bitmap: Bitmap, val contentTop: Int, val contentBottom: Int)

    /** Та же четверть, что уходит на принтер, в уменьшенном виде ([scale] от 300 dpi). */
    fun renderPreview(w: Weather, city: String, footer: String, scale: Float = 0.6f): Preview {
        val qw = W / 2f
        val qh = H / 2f
        val bmp = Bitmap.createBitmap((qw * scale).toInt(), (qh * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(PAD, PAD)
        drawForecast(c, w, city, footer, qw - 2 * PAD, qh - 2 * PAD)
        return Preview(bmp, (PAD * scale).toInt(), ((qh - PAD) * scale).toInt())
    }

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

    /** Растр одной четверти для ESC/P-R: картинка и её положение в печатной области (точки 360 dpi). */
    class Quarter(val bitmap: Bitmap, val x: Int, val y: Int)

    /**
     * Рисует ту же четверть, что и [renderJpeg], но сразу в 360 dpi и только её саму — для печати
     * через [EscprClient]. Участки за полями принтера (3 мм) обрезаются.
     */
    fun renderQuarter(w: Weather, quadrant: Int, city: String, footer: String): Quarter {
        val e = EscprClient
        val qw = e.PAPER_W / 2
        val qh = e.PAPER_H / 2
        val qx = (quadrant % 2) * qw
        val qy = (quadrant / 2) * qh
        // Пересечение четверти с печатной областью, в точках листа
        val x0 = maxOf(qx, e.MARGIN)
        val y0 = maxOf(qy, e.MARGIN)
        val x1 = minOf(qx + qw, e.PAPER_W - e.MARGIN)
        val y1 = minOf(qy + qh, e.PAPER_H - e.MARGIN)

        val bmp = Bitmap.createBitmap(x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.translate((qx - x0).toFloat(), (qy - y0).toFloat())
        // Макет нарисован в единицах 300 dpi — масштабируем до 360.
        c.scale(e.DPI / 300f, e.DPI / 300f)
        c.translate(PAD, PAD)
        drawForecast(c, w, city, footer, W / 2f - 2 * PAD, H / 2f - 2 * PAD)
        return Quarter(bmp, x0 - e.MARGIN, y0 - e.MARGIN)
    }

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun drawForecast(c: Canvas, w: Weather, city: String, footer: String, fullW: Float, ch: Float) {
        // Цветной корешок во всю высоту карточки, текст — правее
        val ink = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeWidth = SPINE_LINE }
        INKS.forEachIndexed { i, (_, col) ->
            ink.color = col
            val x = SPINE_LINE / 2 + i * (SPINE_LINE + SPINE_GAP)
            c.drawLine(x, SPINE_LINE / 2, x, ch - SPINE_LINE / 2, ink)
        }
        val spineW = INKS.size * SPINE_LINE + (INKS.size - 1) * SPINE_GAP + SPINE_TEXT_GAP
        c.save()
        c.translate(spineW, 0f)
        drawContent(c, w, city, footer, fullW - spineW, ch)
        c.restore()
    }

    private fun drawContent(c: Canvas, w: Weather, city: String, footer: String, cw: Float, ch: Float) {
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
