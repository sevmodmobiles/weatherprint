package ru.home.weatherprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Схема листа: две стороны по 4 четверти (проходы 1–8).
 * Серые — заполнены, синяя — сюда ляжет следующий прогноз. Нажатие на четверть вызывает [onPick].
 */
class SheetView(context: Context) : View(context) {
    var pass = 0
        set(v) { field = v; invalidate() }
    var onPick: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cells = Array(8) { RectF() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.78f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val top = 28 * d
        val gap = 16 * d
        val sheetW = (width - gap) / 2
        val sheetH = minOf(sheetW * 1.414f, height - top)
        val sw = sheetH / 1.414f
        for (side in 0..1) {
            val x0 = side * (sheetW + gap) + (sheetW - sw) / 2
            text.textSize = 13 * d; text.color = Ui.SUB; text.typeface = Typeface.DEFAULT
            canvas.drawText(if (side == 0) "Лицевая сторона" else "Оборот", x0 + sw / 2, 16 * d, text)
            fill.color = Color.WHITE
            border.color = Ui.LINE
            val sheet = RectF(x0, top, x0 + sw, top + sheetH)
            canvas.drawRoundRect(sheet, 10 * d, 10 * d, fill)
            canvas.drawRoundRect(sheet, 10 * d, 10 * d, border)
            for (q in 0..3) {
                val idx = side * 4 + q
                val inset = 4 * d
                val l = x0 + (q % 2) * sw / 2
                val t = top + (q / 2) * sheetH / 2
                val r = cells[idx]
                r.set(l + inset, t + inset, l + sw / 2 - inset, t + sheetH / 2 - inset)
                fill.color = when {
                    idx < pass -> Color.rgb(229, 231, 235)
                    idx == pass -> Ui.ACCENT
                    else -> Ui.BG
                }
                canvas.drawRoundRect(r, 7 * d, 7 * d, fill)
                text.textSize = 18 * d
                text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                text.color = when { idx == pass -> Color.WHITE; idx < pass -> Ui.OFF; else -> Ui.SUB }
                canvas.drawText("${idx + 1}", r.centerX(), r.centerY() + 6 * d, text)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pick = onPick ?: return false
        if (e.action == MotionEvent.ACTION_UP) {
            val idx = cells.indexOfFirst { it.contains(e.x, e.y) }
            if (idx >= 0) { pick(idx); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
