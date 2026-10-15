package ru.home.weatherprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Тестовая страница качества печати на целый лист A4: заливки всеми чернилами, градиенты,
 * шкала серого, спектр, тонкие линии и мелкий текст. По ней видно полосы, пропуски дюз,
 * смещение цветов и нечёткость.
 */
object TestPage {
    /** Печатная область A4 при 360 dpi (лист минус поля 3 мм). */
    private const val W = EscprClient.PRINTABLE_W
    private const val H = EscprClient.PRINTABLE_H
    private const val BAND = 256

    /** Лист по полосам — для [EscprClient.printParts], чтобы не держать в памяти весь лист. */
    fun bands(): Sequence<EscprClient.Part> = sequence {
        val time = LocalDateTime.now()
        var top = 0
        while (top < H) {
            val h = minOf(BAND, H - top)
            val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.translate(0f, -top.toFloat())
            draw(c, time)
            yield(EscprClient.Part(bmp, 0, top))
            top += h
        }
    }

    /** Тот же лист целиком в JPEG 300 dpi — для принтеров с AirPrint (IPP). */
    fun renderJpeg(): ByteArray {
        val w = 2480
        val h = 3508
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val k = 300f / EscprClient.DPI
        c.scale(k, k)
        c.translate(EscprClient.MARGIN.toFloat(), EscprClient.MARGIN.toFloat())
        draw(c, LocalDateTime.now())
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun text(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fill(color: Int) = Paint().apply { this.color = color; style = Paint.Style.FILL }

    private fun section(c: Canvas, title: String, y: Float) =
        c.drawText(title, 0f, y, text(46f, bold = true))

    /** Рисует лист в точках 360 dpi относительно печатной области. */
    private fun draw(c: Canvas, time: LocalDateTime) {
        val gray = Color.rgb(90, 90, 90)

        c.drawText("Тест качества печати", 0f, 90f, text(84f, bold = true))
        c.drawText(
            "WeatherPrint · " + time.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
            0f, 150f, text(40f, color = gray)
        )

        // 1. Чистые чернила — все 6 цветов L805
        section(c, "Чернила", 260f)
        val inks = listOf(
            "Голубой C" to Color.rgb(0, 255, 255),
            "Пурпурный M" to Color.rgb(255, 0, 255),
            "Жёлтый Y" to Color.rgb(255, 255, 0),
            "Чёрный K" to Color.BLACK,
            "Светло-голубой" to Color.rgb(170, 235, 255),
            "Светло-пурпурный" to Color.rgb(255, 185, 240),
        )
        val gap = 30f
        val pw = (W - gap * (inks.size - 1)) / inks.size
        inks.forEachIndexed { i, (name, col) ->
            val x = i * (pw + gap)
            c.drawRect(x, 300f, x + pw, 600f, fill(col))
            c.drawText(name, x, 650f, text(36f, color = gray))
        }

        // 2. Плавные переходы от белого к цвету: ступеньки и полосы видны сразу
        section(c, "Градиенты", 760f)
        val ramps = listOf(
            Color.rgb(0, 255, 255), Color.rgb(255, 0, 255), Color.rgb(255, 255, 0), Color.BLACK,
            Color.rgb(255, 0, 0), Color.rgb(0, 160, 0), Color.rgb(0, 0, 255),
        )
        ramps.forEachIndexed { i, col ->
            val top = 800f + i * 120f
            val p = Paint().apply {
                shader = LinearGradient(0f, 0f, W.toFloat(), 0f, Color.WHITE, col, Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, top, W.toFloat(), top + 90f, p)
        }

        // 3. Шкала серого 0–100 %
        section(c, "Шкала серого", 1720f)
        val steps = 11
        val sw = W / steps.toFloat()
        for (i in 0 until steps) {
            val v = 255 - Math.round(255f * i / (steps - 1))
            c.drawRect(i * sw, 1760f, (i + 1) * sw, 1960f, fill(Color.rgb(v, v, v)))
            c.drawText("${i * 10}%", i * sw + 20f, 2010f, text(34f, color = gray))
        }
        val frame = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(160, 160, 160); strokeWidth = 2f }
        c.drawRect(0f, 1760f, W.toFloat(), 1960f, frame)

        // 4. Спектр: оттенки по горизонтали, от светлых к тёмным по вертикали
        section(c, "Спектр", 2100f)
        val hues = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
        )
        c.drawRect(0f, 2140f, W.toFloat(), 2540f, Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 2140f, W.toFloat(), 2540f, Paint().apply {
            shader = LinearGradient(
                0f, 2140f, 0f, 2540f,
                intArrayOf(Color.WHITE, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        })

        // 5. Тонкие линии толщиной 1–4 точки (1 точка ≈ 0,07 мм): чёткость и выравнивание головки
        section(c, "Тонкие линии", 2640f)
        val black = fill(Color.BLACK)
        val half = W / 2
        for (w in 1..4) {
            val gx = (w - 1) * (half / 4)
            c.drawText("$w", gx.toFloat(), 2720f, text(34f, color = gray))
            // горизонтальные
            for (k in 0 until 12) {
                val y = 2750 + k * w * 3
                c.drawRect(gx.toFloat(), y.toFloat(), (gx + half / 4 - 60).toFloat(), (y + w).toFloat(), black)
            }
            // вертикальные
            val vx = half + (w - 1) * (half / 4)
            c.drawText("$w", vx.toFloat(), 2720f, text(34f, color = gray))
            for (k in 0 until 24) {
                val x = vx + k * w * 3
                c.drawRect(x.toFloat(), 2750f, (x + w).toFloat(), 3050f, black)
            }
        }

        // 6. Мелкий текст 6–14 пт
        section(c, "Текст", 3180f)
        var y = 3180f
        for (pt in listOf(6, 8, 10, 12, 14)) {
            val size = pt * EscprClient.DPI / 72f
            y += size * 1.5f
            c.drawText("$pt пт  Съешь же ещё этих мягких французских булок 0123456789", 0f, y, text(size))
        }

        c.drawText(
            "Полосы или пропуски в заливках и на линиях — повод сделать проверку дюз и прочистку.",
            0f, H - 30f, text(36f, color = gray)
        )
    }
}
