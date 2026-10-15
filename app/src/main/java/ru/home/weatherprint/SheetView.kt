package ru.home.weatherprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Схема листа: две стороны по 4 четверти. Серые — заполнены, зелёная — следующая. */
class SheetView(context: Context) : View(context) {
    var pass = 0
        set(v) { field = v; invalidate() }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.DKGRAY
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f; color = Color.DKGRAY; textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val gap = width * 0.08f
        val sheetW = (width - gap * 3) / 2
        val sheetH = sheetW * 1.414f
        val maxH = height - 60f
        val scale = if (sheetH > maxH) maxH / sheetH else 1f
        val sw = sheetW * scale
        val sh = sheetH * scale
        for (side in 0..1) {
            val x0 = gap + side * (sheetW + gap) + (sheetW - sw) / 2
            val y0 = 50f
            canvas.drawText(if (side == 0) "Лицевая сторона" else "Оборот", x0 + sw / 2, 36f, text)
            for (q in 0..3) {
                val idx = side * 4 + q
                val l = x0 + (q % 2) * sw / 2
                val t = y0 + (q / 2) * sh / 2
                val r = RectF(l, t, l + sw / 2, t + sh / 2)
                fill.color = when {
                    idx < pass -> Color.rgb(200, 200, 200)
                    idx == pass -> Color.rgb(120, 200, 120)
                    else -> Color.WHITE
                }
                canvas.drawRect(r, fill)
                canvas.drawRect(r, border)
                canvas.drawText("${idx + 1}", r.centerX(), r.centerY() + 12f, text)
            }
        }
    }
}
