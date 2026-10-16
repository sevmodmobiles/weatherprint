package ru.home.weatherprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Модель и картинка выбранного принтера.
 *
 * Принтеры с AirPrint/IPP сами отдают свои изображения (атрибут printer-icons) — это фото
 * от производителя. Если принтер картинку не отдаёт (как L805 без AirPrint), рисуем
 * подходящую иллюстрацию в [PrinterArt].
 */
object PrinterInfo {

    private const val ICON_FILE = "printer_icon.png"

    fun iconFile(ctx: Context) = File(ctx.filesDir, ICON_FILE)

    /** Сохранённая картинка принтера или null. */
    fun icon(ctx: Context): Bitmap? = iconFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** «EPSON L805 Series» → «Epson L805». */
    fun cleanModel(raw: String): String = raw
        .replace(Regex("\\s*\\(.*\\)\\s*$"), "")
        .replace(Regex("\\s+Series$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^EPSON\\b"), "Epson")
        .replace(Regex("^HEWLETT[- ]PACKARD\\b|^HP\\b", RegexOption.IGNORE_CASE), "HP")
        .replace(Regex("^CANON\\b"), "Canon")
        .replace(Regex("^BROTHER\\b"), "Brother")
        .trim()

    /** Модель известна точно (есть номер), а не только марка. */
    fun modelKnown(model: String) = model.any { it.isDigit() }

    /** Принтер с баками для чернил (Epson L / EcoTank, Canon G, HP Smart Tank, Brother DCP-T) — для иллюстрации. */
    fun isInkTank(model: String): Boolean =
        Regex("(?i)\\b(L\\d{3,5}|ET-?\\d+|EcoTank|Smart Tank|PIXMA G\\d+|DCP-T\\d+|MFC-T\\d+)\\b").containsMatchIn(model)

    /**
     * Определяет модель и пробует скачать картинку. Вызывается после выбора принтера
     * и при первом открытии экрана принтера, если модель ещё не известна. Работает с сетью — не в UI-потоке.
     */
    fun identify(ctx: Context) {
        val p = Prefs(ctx)
        val lan = LocalNet.route(ctx)
        iconFile(ctx).delete()

        if (p.protocol == "escpr") {
            // Веб-интерфейс часто называет принтер просто «EPSON» — точную модель спрашиваем по SNMP
            val snmp = PrinterStatus.modelName(p.printerHost, lan)
            p.printerModel = cleanModel(snmp ?: p.printerName.ifEmpty { "Epson" })
            // У части Epson картинка лежит в веб-интерфейсе; у L805 обычно нет — тогда будет иллюстрация.
            for (path in listOf("/PRESENTATION/AIRPRINT/PRINTER_128.PNG", "/PRESENTATION/AIRPRINT/PRINTER_512.PNG")) {
                if (download(ctx, "http://${p.printerHost}$path", lan)) break
            }
            return
        }

        val attrs = runCatching { IppClient(p.printerHost, p.ippPath, lan).getAttributes() }.getOrNull() ?: return
        attrs["printer-make-and-model"]?.firstOrNull()?.let { p.printerModel = cleanModel(it) }
        // Иконки идут от маленькой к большой — берём самую крупную, что скачается
        val icons = attrs["printer-icons"].orEmpty().reversed()
        for (uri in icons) {
            // Ссылки часто вида https://имя.local/...: подставляем IP и пробуем http, затем https
            val u = runCatching { URL(uri) }.getOrNull() ?: continue
            val tail = u.file
            val candidates = listOf(
                "http://${p.printerHost}$tail",
                "https://${p.printerHost}$tail",
                "http://${p.printerHost}:631$tail",
                uri,
            )
            if (candidates.any { download(ctx, it, lan) }) return
        }
    }

    private fun download(ctx: Context, url: String, lan: Network?): Boolean = runCatching {
        val u = URL(url)
        val conn = (lan?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = 3000; conn.readTimeout = 5000
            if (conn.responseCode != 200) return@runCatching false
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching false
            iconFile(ctx).writeBytes(bytes)
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
