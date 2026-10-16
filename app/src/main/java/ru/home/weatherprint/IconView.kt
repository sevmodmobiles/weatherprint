package ru.home.weatherprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Icon { GEAR, PRINTER, CLOCK, BACK, CHEVRON, PAPER, PIN, DOWNLOAD, DROP, SEARCH, EYE, LINES, WIFI }

/** Простые линейные значки, нарисованные кодом (сетка 24×24, как у Material Icons). */
class IconView(context: Context) : View(context) {

    var icon = Icon.GEAR
        set(v) { field = v; invalidate() }
    var color = Color.BLACK
        set(v) { field = v; invalidate() }
    /** Цвет фона под значком — им «вырезаются» перекрытия (лист в принтере). */
    var bg = Color.WHITE

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val s = min(w, h) / 24f
        c.save()
        c.translate(paddingLeft + (w - 24 * s) / 2, paddingTop + (h - 24 * s) / 2)
        c.scale(s, s)
        stroke.color = color
        stroke.strokeWidth = 2f
        fill.color = color
        path.reset()

        when (icon) {
            Icon.CHEVRON -> c.line(9f, 6f, 15f, 12f, 9f, 18f)
            Icon.BACK -> {
                c.line(5f, 12f, 19f, 12f)
                c.line(11f, 6f, 5f, 12f, 11f, 18f)
            }
            Icon.CLOCK -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                c.line(12f, 7f, 12f, 12f, 15.5f, 14f)
            }
            Icon.PRINTER -> {
                c.drawRect(7f, 3.5f, 17f, 9f, stroke)
                c.drawRoundRect(RectF(3f, 9f, 21f, 17.5f), 2.5f, 2.5f, stroke)
                fill.color = bg
                c.drawRect(7f, 14f, 17f, 20.5f, fill)
                c.drawRect(7f, 14f, 17f, 20.5f, stroke)
                fill.color = color
                c.drawCircle(17.5f, 12f, 1.1f, fill)
            }
            Icon.GEAR -> {
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    fun pt(r: Double, da: Double) = Pair(
                        (12 + r * cos(a + Math.toRadians(da))).toFloat(),
                        (12 + r * sin(a + Math.toRadians(da))).toFloat()
                    )
                    val p1 = pt(7.2, -15.0); val p2 = pt(9.6, -9.0); val p3 = pt(9.6, 9.0); val p4 = pt(7.2, 15.0)
                    if (i == 0) path.moveTo(p1.first, p1.second) else path.lineTo(p1.first, p1.second)
                    path.lineTo(p2.first, p2.second); path.lineTo(p3.first, p3.second); path.lineTo(p4.first, p4.second)
                }
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(12f, 12f, 3f, stroke)
            }
            Icon.PAPER -> {
                path.moveTo(6f, 3f); path.lineTo(14f, 3f); path.lineTo(18f, 7f); path.lineTo(18f, 21f)
                path.lineTo(6f, 21f); path.close()
                c.drawPath(path, stroke)
                c.line(9f, 11f, 15f, 11f)
                c.line(9f, 15f, 15f, 15f)
            }
            Icon.PIN -> {
                path.addArc(RectF(5f, 3f, 19f, 17f), 140f, 260f)
                path.lineTo(12f, 21.5f)
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(12f, 10f, 2.5f, stroke)
            }
            Icon.DOWNLOAD -> {
                c.line(12f, 4f, 12f, 15f)
                c.line(7f, 10f, 12f, 15f, 17f, 10f)
                c.line(5f, 20f, 19f, 20f)
            }
            Icon.DROP -> {
                path.moveTo(12f, 3f)
                path.cubicTo(12f, 3f, 5f, 10.5f, 5f, 15f)
                path.cubicTo(5f, 19f, 8.1f, 21.5f, 12f, 21.5f)
                path.cubicTo(15.9f, 21.5f, 19f, 19f, 19f, 15f)
                path.cubicTo(19f, 10.5f, 12f, 3f, 12f, 3f)
                path.close()
                c.drawPath(path, stroke)
            }
            Icon.SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6.5f, stroke)
                c.line(15.5f, 15.5f, 20f, 20f)
            }
            Icon.EYE -> {
                path.moveTo(2f, 12f)
                path.quadTo(12f, 2f, 22f, 12f)
                path.quadTo(12f, 22f, 2f, 12f)
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(12f, 12f, 3f, stroke)
            }
            Icon.LINES -> {
                c.line(6f, 4f, 6f, 20f)
                c.line(12f, 4f, 12f, 10f)
                c.line(12f, 14f, 12f, 20f)
                c.line(18f, 4f, 18f, 20f)
            }
            Icon.WIFI -> {
                c.drawArc(RectF(2f, 5f, 22f, 25f), 225f, 90f, false, stroke)
                c.drawArc(RectF(6f, 9f, 18f, 21f), 225f, 90f, false, stroke)
                c.drawCircle(12f, 18.5f, 1.4f, fill)
            }
        }
        c.restore()
    }

    /** Ломаная через точки x1,y1,x2,y2,... */
    private fun Canvas.line(vararg p: Float) {
        val pt = Path()
        pt.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { pt.lineTo(p[i], p[i + 1]); i += 2 }
        drawPath(pt, stroke)
    }
}
