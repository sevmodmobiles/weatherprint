package ru.home.weatherprint

import android.graphics.Bitmap

/**
 * Примерная оценка, какие из 6 чернил L805 задействует картинка.
 *
 * Настоящую раскладку RGB → чернила делает сам принтер, и Epson её не публикует, поэтому здесь —
 * упрощённая модель: CMYK с выносом чёрного, а голубой и пурпурный делятся на светлые чернила
 * (бледные оттенки) и основные (насыщенные). Для вопроса «все ли цвета в деле» этого достаточно.
 */
object InkCheck {

    /**
     * [coverage] — сколько чернил в пересчёте на сплошную заливку, % площади карточки.
     * [height] — на какой доле высоты карточки цвет встречается, %: колонки дюз вертикальные,
     * и чтобы прокачивались все дюзы цвета, он должен идти по всей высоте.
     */
    class Ink(val name: String, val color: Int, val coverage: Double, val height: Double) {
        val ok get() = height >= 90 && coverage >= 0.05
        val unused get() = coverage < 0.01
    }

    /** Порядок как в [PageRenderer.INKS]: C, M, Y, K, LC, LM. */
    fun analyze(bmp: Bitmap, top: Int, bottom: Int): List<Ink> {
        val w = bmp.width
        val rows = (bottom - top).coerceAtLeast(1)
        val sum = DoubleArray(6)
        val rowsWith = IntArray(6)
        val row = IntArray(w)
        val inRow = BooleanArray(6)
        val amt = DoubleArray(6)

        for (y in top until bottom.coerceAtMost(bmp.height)) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            inRow.fill(false)
            for (px in row) {
                if ((px and 0xFFFFFF) == 0xFFFFFF) continue
                split(px, amt)
                for (i in 0 until 6) {
                    sum[i] += amt[i]
                    if (amt[i] > 0.15) inRow[i] = true
                }
            }
            for (i in 0 until 6) if (inRow[i]) rowsWith[i]++
        }

        val area = w.toDouble() * rows
        return PageRenderer.INKS.mapIndexed { i, (name, color) ->
            Ink(name, color, sum[i] / area * 100, rowsWith[i] * 100.0 / rows)
        }
    }

    /** Цвет пикселя → доли чернил C, M, Y, K, LC, LM (0..1). */
    private fun split(px: Int, out: DoubleArray) {
        val r = (px shr 16 and 0xFF) / 255.0
        val g = (px shr 8 and 0xFF) / 255.0
        val b = (px and 0xFF) / 255.0
        val k = 1 - maxOf(r, g, b)
        if (k > 0.999) {
            out.fill(0.0); out[3] = 1.0
            return
        }
        val c = (1 - r - k) / (1 - k)
        val m = (1 - g - k) / (1 - k)
        val y = (1 - b - k) / (1 - k)
        out[0] = dark(c); out[4] = light(c)
        out[1] = dark(m); out[5] = light(m)
        out[2] = y
        out[3] = k
    }

    /** До 40 % плотности печатает светлыми чернилами, дальше всё больше основными. */
    private fun light(v: Double) = if (v <= 0.4) v / 0.4 else (1 - v) / 0.6
    private fun dark(v: Double) = if (v <= 0.4) 0.0 else (v - 0.4) / 0.6
}
