package ru.home.weatherprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Большая карточка погоды на главном экране: фон под погоду, рисованная иконка,
 * крупная температура, почасовой прогноз и три дня вперёд. Занимает ~55% высоты экрана.
 */
class WeatherView(context: Context) : View(context) {

    var weather: Weather? = null
        set(v) { field = v; message = null; invalidate() }
    var city: String = ""
        set(v) { field = v; invalidate() }
    var message: String? = "Загружаю погоду…"
        set(v) { field = v; invalidate() }

    private val ru = Locale("ru")
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    private fun kind(code: Int) = when (code) {
        0 -> Kind.CLEAR
        1, 2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        68, 69, 71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUDY
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val screenH = resources.displayMetrics.heightPixels
        setMeasuredDimension(w, (screenH * 0.48f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val wx = weather
        val k = wx?.let { kind(it.code) } ?: Kind.CLOUDY
        val night = wx?.isDay == false

        // Фон
        val (top, bottom) = when {
            night && (k == Kind.CLEAR || k == Kind.PARTLY) -> 0xFF1B2845.toInt() to 0xFF3E5180.toInt()
            night -> 0xFF232B3E.toInt() to 0xFF4A5570.toInt()
            k == Kind.CLEAR -> 0xFF2F8CF0.toInt() to 0xFF8CCBFF.toInt()
            k == Kind.PARTLY -> 0xFF4A90D9.toInt() to 0xFFA9CDEB.toInt()
            k == Kind.CLOUDY || k == Kind.FOG -> 0xFF6E7F99.toInt() to 0xFFB3C0D1.toInt()
            k == Kind.RAIN -> 0xFF44566F.toInt() to 0xFF8295AD.toInt()
            k == Kind.SNOW -> 0xFF7FA3CF.toInt() to 0xFFD7E6F6.toInt()
            else -> 0xFF363B52.toInt() to 0xFF6D7189.toInt()
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w, h), dp(24f), dp(24f), fill)
        fill.shader = null

        val pad = dp(20f)

        // Шапка: город и дата
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = dp(20f); text.alpha = 255
        c.drawText(city, pad, pad + dp(20f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(14f); text.alpha = 220
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru))
            .replaceFirstChar { it.uppercase(ru) }
        c.drawText(date, pad, pad + dp(42f), text)
        text.alpha = 255

        if (wx == null) {
            text.textSize = dp(16f)
            c.drawText(message ?: "", pad, h / 2, text)
            return
        }

        // Иконка справа сверху
        val iconSize = min(w * 0.38f, h * 0.34f)
        drawIcon(c, k, night, w - pad - iconSize / 2, pad + dp(12f) + iconSize / 2, iconSize)

        // Большая температура
        val bigY = pad + dp(60f) + h * 0.22f
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = h * 0.24f
        c.drawText(WeatherApi.fmtTemp(wx.temp), pad - dp(4f), bigY, text)

        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = dp(19f)
        c.drawText(WeatherApi.describe(wx.code), pad, bigY + dp(30f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(14f); text.alpha = 225
        val today = wx.days.firstOrNull()
        val line2 = buildString {
            append("Ощущается ${WeatherApi.fmtTemp(wx.feels)}")
            if (today != null) append(" · ${WeatherApi.fmtTemp(today.tMin)}…${WeatherApi.fmtTemp(today.tMax)}")
        }
        c.drawText(line2, pad, bigY + dp(52f), text)
        val line3 = "Ветер ${Math.round(wx.wind)} м/с · влажность ${wx.humidity}%" +
            (today?.let { " · осадки ${it.precipProb}%" } ?: "")
        c.drawText(line3, pad, bigY + dp(72f), text)
        text.alpha = 255

        // Нижняя панель: почасовой прогноз и дни
        val panelTop = bigY + dp(90f)
        val panel = RectF(pad / 2, panelTop, w - pad / 2, h - pad / 2)
        fill.color = Color.argb(45, 255, 255, 255)
        c.drawRoundRect(panel, dp(18f), dp(18f), fill)

        val hours = wx.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(6)
        val days = wx.days.drop(1).take(3)
        val split = if (days.isNotEmpty()) panel.top + panel.height() * 0.56f else panel.bottom
        if (hours.isNotEmpty()) {
            val cw = panel.width() / hours.size
            val rowH = split - panel.top
            hours.forEachIndexed { i, hr ->
                val cx = panel.left + cw * i + cw / 2
                text.textAlign = Paint.Align.CENTER
                text.textSize = dp(12f); text.alpha = 210
                val label = if (i == 0) "Сейчас" else hr.time.format(DateTimeFormatter.ofPattern("HH:mm"))
                c.drawText(label, cx, panel.top + rowH * 0.24f, text)
                text.alpha = 255
                drawIcon(c, kind(hr.code), hr.time.hour !in 7..19, cx, panel.top + rowH * 0.52f, rowH * 0.34f)
                text.textSize = dp(15f)
                c.drawText(WeatherApi.fmtTemp(hr.temp), cx, panel.top + rowH * 0.9f, text)
            }
        }
        if (days.isNotEmpty()) {
            fill.color = Color.argb(60, 255, 255, 255)
            c.drawRect(panel.left + dp(14f), split, panel.right - dp(14f), split + dp(1f), fill)
            val cw = panel.width() / days.size
            val rowH = panel.bottom - split
            days.forEachIndexed { i, dd ->
                val x0 = panel.left + cw * i
                val cy = split + rowH / 2
                drawIcon(c, kind(dd.code), false, x0 + dp(26f), cy, min(rowH * 0.5f, dp(30f)))
                text.textAlign = Paint.Align.LEFT
                text.textSize = dp(13f); text.alpha = 215
                c.drawText(dd.date.format(DateTimeFormatter.ofPattern("EE, d", ru)), x0 + dp(48f), cy - dp(3f), text)
                text.alpha = 255
                text.textSize = dp(14f)
                text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                c.drawText("${WeatherApi.fmtTemp(dd.tMin)}…${WeatherApi.fmtTemp(dd.tMax)}", x0 + dp(48f), cy + dp(15f), text)
                text.typeface = Typeface.DEFAULT
            }
        }
        text.textAlign = Paint.Align.LEFT
    }

    // ---------- Рисованные иконки ----------

    private fun drawIcon(c: Canvas, k: Kind, night: Boolean, cx: Float, cy: Float, size: Float) {
        val s = size
        when (k) {
            Kind.CLEAR -> if (night) moon(c, cx, cy, s * 0.36f) else sun(c, cx, cy, s * 0.26f)
            Kind.PARTLY -> {
                if (night) moon(c, cx + s * 0.16f, cy - s * 0.14f, s * 0.24f)
                else sun(c, cx + s * 0.16f, cy - s * 0.14f, s * 0.19f)
                cloud(c, cx - s * 0.06f, cy + s * 0.12f, s * 0.78f, Color.WHITE)
            }
            Kind.CLOUDY -> {
                cloud(c, cx + s * 0.12f, cy - s * 0.1f, s * 0.6f, Color.argb(255, 215, 222, 232))
                cloud(c, cx - s * 0.04f, cy + s * 0.08f, s * 0.82f, Color.WHITE)
            }
            Kind.FOG -> {
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, Color.WHITE)
                stroke.color = Color.argb(230, 255, 255, 255); stroke.strokeWidth = s * 0.06f
                for (i in 0..2) {
                    val y = cy + s * (0.2f + i * 0.12f)
                    c.drawLine(cx - s * 0.36f + i * s * 0.06f, y, cx + s * 0.36f - i * s * 0.04f, y, stroke)
                }
            }
            Kind.RAIN -> {
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, Color.WHITE)
                stroke.color = Color.rgb(120, 200, 255); stroke.strokeWidth = s * 0.055f
                for (i in -1..1) {
                    val x = cx + i * s * 0.2f
                    c.drawLine(x, cy + s * 0.2f, x - s * 0.07f, cy + s * 0.38f, stroke)
                }
            }
            Kind.SNOW -> {
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, Color.WHITE)
                fill.color = Color.WHITE
                for (i in -1..1) {
                    val x = cx + i * s * 0.2f
                    flake(c, x, cy + s * (0.3f + if (i == 0) 0.06f else 0f), s * 0.07f)
                }
            }
            Kind.STORM -> {
                cloud(c, cx, cy - s * 0.12f, s * 0.84f, Color.argb(255, 220, 224, 235))
                val p = Path().apply {
                    moveTo(cx + s * 0.04f, cy + s * 0.08f)
                    lineTo(cx - s * 0.12f, cy + s * 0.3f)
                    lineTo(cx + s * 0.0f, cy + s * 0.3f)
                    lineTo(cx - s * 0.08f, cy + s * 0.48f)
                    lineTo(cx + s * 0.14f, cy + s * 0.22f)
                    lineTo(cx + s * 0.02f, cy + s * 0.22f)
                    close()
                }
                fill.color = Color.rgb(255, 213, 74)
                c.drawPath(p, fill)
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.color = Color.rgb(255, 205, 60); stroke.strokeWidth = r * 0.22f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(
                cx + (r * 1.35f * cos(a)).toFloat(), cy + (r * 1.35f * sin(a)).toFloat(),
                cx + (r * 1.75f * cos(a)).toFloat(), cy + (r * 1.75f * sin(a)).toFloat(), stroke
            )
        }
        fill.shader = LinearGradient(cx, cy - r, cx, cy + r,
            Color.rgb(255, 222, 89), Color.rgb(255, 165, 30), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            val cut = Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.85f, Path.Direction.CW) }
            op(cut, Path.Op.DIFFERENCE)
        }
        fill.color = Color.rgb(250, 240, 200)
        c.drawPath(p, fill)
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, w: Float, color: Int) {
        val h = w * 0.42f
        val p = Path().apply {
            addRoundRect(RectF(cx - w / 2, cy, cx + w / 2, cy + h * 0.55f), h * 0.28f, h * 0.28f, Path.Direction.CW)
            addCircle(cx - w * 0.2f, cy + h * 0.05f, h * 0.38f, Path.Direction.CW)
            addCircle(cx + w * 0.08f, cy - h * 0.12f, h * 0.55f, Path.Direction.CW)
            addCircle(cx + w * 0.3f, cy + h * 0.12f, h * 0.32f, Path.Direction.CW)
        }
        fill.color = Color.argb(40, 0, 0, 40)
        c.save(); c.translate(0f, w * 0.03f); c.drawPath(p, fill); c.restore()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun flake(c: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.color = Color.WHITE; stroke.strokeWidth = r * 0.35f
        for (i in 0 until 3) {
            val a = Math.toRadians(i * 60.0)
            val dx = (r * cos(a)).toFloat(); val dy = (r * sin(a)).toFloat()
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke)
        }
    }
}
