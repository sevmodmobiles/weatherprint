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

    fun iconFile(ctx: Context, printerId: String = Printers.active(ctx)) = File(ctx.filesDir, "printer_icon_$printerId.png")

    /** Сохранённая картинка принтера или null. */
    fun icon(ctx: Context, printerId: String = Printers.active(ctx)): Bitmap? =
        iconFile(ctx, printerId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    // ---------- Уникальный номер принтера ----------

    /**
     * Уникальный номер принтера по адресу [host]: UUID из IPP, MAC-адрес или серийный номер по SNMP.
     * Нужен, чтобы не перепутать свой принтер с чужим той же модели или с чужим принтером на том же IP.
     */
    fun readIdentity(host: String, protocol: String, path: String, lan: android.net.Network?): String? {
        if (protocol != "escpr") {
            runCatching { IppClient(host, path, lan).getAttributes() }.getOrNull()
                ?.get("printer-uuid")?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { return "uuid:" + it.removePrefix("urn:uuid:").lowercase() }
        }
        PrinterStatus.macAddress(host, lan)?.let { return "mac:$it" }
        PrinterStatus.serialNumber(host, lan)?.let { return "sn:$it" }
        return null
    }

    /**
     * Тот ли это принтер: true — номер совпал, false — по этому адресу другой принтер,
     * null — проверить нельзя (номер не сохранён или принтер его не сообщает).
     */
    fun verify(p: Prefs, lan: android.net.Network?): Boolean? {
        val saved = p.printerIdentity
        if (saved.isEmpty()) return null
        val now = readIdentity(p.printerHost, p.protocol, p.ippPath, lan) ?: return null
        return now == saved
    }

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
    fun identify(ctx: Context, printerId: String = Printers.active(ctx), learnIdentity: Boolean = false) {
        val p = Prefs(ctx, printerId)
        val lan = LocalNet.route(ctx)
        // Номер запоминаем только когда человек сам выбрал принтер или проверил связь — то есть он рядом с ним
        if (learnIdentity || p.printerIdentity.isEmpty()) {
            readIdentity(p.printerHost, p.protocol, p.ippPath, lan)?.let { p.printerIdentity = it }
        }
        iconFile(ctx, printerId).delete()

        if (p.protocol == "escpr") {
            // Веб-интерфейс часто называет принтер просто «EPSON» — точную модель спрашиваем по SNMP
            val snmp = PrinterStatus.modelName(p.printerHost, lan)
            p.printerModel = cleanModel(snmp ?: p.printerName.ifEmpty { "Epson" })
            // У части Epson картинка лежит в веб-интерфейсе; у L805 обычно нет — тогда будет иллюстрация.
            for (path in listOf("/PRESENTATION/AIRPRINT/PRINTER_128.PNG", "/PRESENTATION/AIRPRINT/PRINTER_512.PNG")) {
                if (download(iconFile(ctx, printerId), "http://${p.printerHost}$path", lan)) break
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
            if (candidates.any { download(iconFile(ctx, printerId), it, lan) }) return
        }
    }

    private fun download(file: File, url: String, lan: Network?): Boolean = runCatching {
        val u = URL(url)
        val conn = (lan?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = 3000; conn.readTimeout = 5000
            if (conn.responseCode != 200) return@runCatching false
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching false
            file.writeBytes(bytes)
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
