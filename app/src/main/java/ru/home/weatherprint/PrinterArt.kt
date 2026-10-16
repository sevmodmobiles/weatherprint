package ru.home.weatherprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Изображение принтера: картинка от самого принтера ([bitmap]) или нарисованная иллюстрация —
 * принтер с баками для чернил (как Epson L805) или обычный струйный.
 */
class PrinterArt(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var inkTank = true
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    /** Загружает картинку и вид иллюстрации для выбранного принтера. */
    fun showFor(ctx: Context, prefs: Prefs) {
        bitmap = PrinterInfo.icon(ctx, prefs.printerId)
        val model = prefs.printerModel.ifEmpty { prefs.printerName }
        // Модель неизвестна — для Epson без AirPrint скорее всего это принтер с баками (L-серия)
        inkTank = PrinterInfo.isInkTank(model) || (!PrinterInfo.modelKnown(model) && prefs.protocol == "escpr")
    }

    override fun onDraw(c: Canvas) {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        bitmap?.let { b ->
            val s = min(w / b.width, h / b.height)
            val bw = b.width * s
            val bh = b.height * s
            val l = paddingLeft + (w - bw) / 2
            val t = paddingTop + (h - bh) / 2
            c.drawBitmap(b, null, RectF(l, t, l + bw, t + bh), p)
            return
        }
        // Иллюстрация в сетке 140×96
        val s = min(w / 140f, h / 96f)
        c.save()
        c.translate(paddingLeft + (w - 140 * s) / 2, paddingTop + (h - 96 * s) / 2)
        c.scale(s, s)
        if (inkTank) drawInkTank(c) else drawGeneric(c)
        c.restore()
    }

    private fun fill(color: Int) = p.apply { style = Paint.Style.FILL; this.color = color; shader = null }
    private fun stroke(color: Int, width: Float) = p.apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = width }

    private fun shadow(c: Canvas, l: Float, r: Float) {
        c.drawOval(RectF(l, 84f, r, 94f), fill(Color.argb(28, 0, 0, 0)))
    }

    /** Лист в заднем лотке. */
    private fun paper(c: Canvas, l: Float, r: Float) {
        path.reset()
        path.moveTo(l, 6f); path.lineTo(r, 6f); path.lineTo(r - 6, 30f); path.lineTo(l + 6, 30f); path.close()
        c.drawPath(path, fill(Color.WHITE))
        c.drawPath(path, stroke(Color.rgb(203, 213, 225), 1.2f))
        for (i in 0..2) c.drawLine(l + 12, 13f + i * 5, r - 14 - i * 8, 13f + i * 5, stroke(Color.rgb(203, 213, 225), 1.2f))
    }

    private fun body(c: Canvas, l: Float, r: Float) {
        val outline = Color.rgb(148, 163, 184)
        val box = RectF(l, 26f, r, 82f)
        c.drawRoundRect(box, 10f, 10f, fill(Color.rgb(241, 245, 249)))
        c.drawRoundRect(RectF(l, 26f, r, 44f), 10f, 10f, fill(Color.rgb(226, 232, 240)))
        c.drawRect(l, 36f, r, 44f, fill(Color.rgb(226, 232, 240)))
        c.drawRoundRect(box, 10f, 10f, stroke(outline, 1.4f))
        c.drawLine(l, 44f, r, 44f, stroke(outline, 1.0f))
        // Щель выхода бумаги и выдвинутый лист
        c.drawRoundRect(RectF(l + 20, 60f, r - 20, 66f), 3f, 3f, fill(Color.rgb(51, 65, 85)))
        c.drawRect(l + 26, 63f, r - 26, 70f, fill(Color.WHITE))
        c.drawRect(l + 26, 63f, r - 26, 70f, stroke(Color.rgb(203, 213, 225), 1f))
        // Кнопка питания с индикатором
        c.drawCircle(r - 10, 52f, 2.6f, fill(Ui.ACCENT))
    }

    private fun drawInkTank(c: Canvas) {
        shadow(c, 8f, 138f)
        paper(c, 30f, 92f)
        body(c, 6f, 112f)
        // Блок баков справа — 6 чернил L805
        val tank = RectF(110f, 34f, 138f, 82f)
        c.drawRoundRect(tank, 7f, 7f, fill(Color.rgb(226, 232, 240)))
        c.drawRoundRect(tank, 7f, 7f, stroke(Color.rgb(148, 163, 184), 1.4f))
        PageRenderer.INKS.forEachIndexed { i, (_, col) ->
            val x = 113.5f + i * 3.6f
            c.drawRoundRect(RectF(x, 42f, x + 2.8f, 76f), 1.2f, 1.2f, fill(Color.WHITE))
            c.drawRoundRect(RectF(x, 52f + (i % 3) * 3f, x + 2.8f, 76f), 1.2f, 1.2f, fill(col))
        }
    }

    private fun drawGeneric(c: Canvas) {
        shadow(c, 10f, 130f)
        paper(c, 40f, 100f)
        body(c, 12f, 128f)
        // Выходной лоток
        path.reset()
        path.moveTo(38f, 82f); path.lineTo(102f, 82f); path.lineTo(108f, 89f); path.lineTo(32f, 89f); path.close()
        c.drawPath(path, fill(Color.rgb(226, 232, 240)))
        c.drawPath(path, stroke(Color.rgb(148, 163, 184), 1.2f))
    }
}
