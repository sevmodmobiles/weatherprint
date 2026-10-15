package ru.home.weatherprint

import android.graphics.Bitmap
import android.net.Network
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDateTime
import javax.net.SocketFactory

/**
 * Печать на Epson по «родному» протоколу ESC/P-R через порт 9100 — так печатает Epson iPrint.
 * Нужен принтерам без AirPrint/IPP (например, L805).
 *
 * Формат команд — по открытому драйверу Epson epson-inkjet-printer-escpr
 * и его Python-порту python-epson (MIT).
 */
class EscprClient(private val host: String, private val network: Network? = null) {

    companion object {
        const val PORT = 9100
        const val DPI = 360
        /** A4 при 360 dpi. */
        const val PAPER_W = 2976
        const val PAPER_H = 4209
        /** Поля 3 мм — обычная печать с полями. */
        const val MARGIN = 42
        const val PRINTABLE_W = PAPER_W - 2 * MARGIN
        const val PRINTABLE_H = PAPER_H - 2 * MARGIN
    }

    private fun socket(timeoutMs: Int): Socket {
        val sf = network?.socketFactory ?: SocketFactory.getDefault()
        val s = sf.createSocket()
        s.connect(InetSocketAddress(host.trim('[', ']'), PORT), timeoutMs)
        return s
    }

    /** Принтер в сети, если принимает соединение на порт 9100. */
    fun ping(timeoutMs: Int = 3000) {
        socket(timeoutMs).close()
    }

    /**
     * Печатает растр [bmp] на листе A4. [x], [y] — положение левого верхнего угла растра
     * в точках 360 dpi относительно печатной области (лист минус поля 3 мм).
     */
    fun print(bmp: Bitmap, x: Int, y: Int) {
        socket(5000).use { s ->
            s.soTimeout = 60_000
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            startJob(out)
            sendPage(out, bmp, x, y)
            endJob(out)
            out.flush()
            // Дочитываем ответ принтера, чтобы не оборвать соединение раньше времени.
            runCatching {
                s.shutdownOutput()
                s.soTimeout = 3000
                val buf = ByteArray(1024)
                val inp = s.getInputStream()
                while (inp.read(buf) >= 0) { /* ждём, пока принтер закроет соединение */ }
            }
        }
    }

    // ---------- Начало и конец задания (режим REMOTE1) ----------

    private fun startJob(out: OutputStream) {
        // Выход из пакетного режима IEEE 1284.4 и сброс принтера
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x1B, 0x01) + "@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, '@'.code.toByte()))

        remoteEnter(out)
        val t = LocalDateTime.now()
        remote(out, "TI", byteArrayOf(
            (t.year shr 8).toByte(), t.year.toByte(),
            t.monthValue.toByte(), t.dayOfMonth.toByte(),
            t.hour.toByte(), t.minute.toByte(), t.second.toByte(),
        ))
        remote(out, "JS", byteArrayOf(0, 0, 0))
        remote(out, "HD", byteArrayOf(0x03, 0x04))
        remote(out, "PP", byteArrayOf(0x01, 0x00)) // задний лоток
        remoteExit(out)

        // Режим ESC/P-R
        out.write(byteArrayOf(0x1B, '('.code.toByte(), 'R'.code.toByte(), 0x06, 0x00, 0x00) + "ESCPR".toByteArray(Charsets.US_ASCII))

        // setq: обычная бумага, нормальное качество, цвет, яркость/контраст/насыщенность 0, 24-бит RGB, без палитры
        cmd(out, 'q', "setq", byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0))

        // setj: размер листа, поля, печатная область (всё в точках), 360 dpi, двунаправленная печать
        val j = ByteArrayOutputStream()
        j.u32(PAPER_W); j.u32(PAPER_H)
        j.u16(MARGIN); j.u16(MARGIN)
        j.u32(PRINTABLE_W); j.u32(PRINTABLE_H)
        j.write(0) // 360 dpi
        j.write(0) // двунаправленная печать
        cmd(out, 'j', "setj", j.toByteArray())
    }

    private fun endJob(out: OutputStream) {
        cmd(out, 'j', "endj", ByteArray(0))
        out.write(byteArrayOf(0x1B, '@'.code.toByte()))
        remoteEnter(out)
        remote(out, "LD", ByteArray(0))
        remote(out, "JE", ByteArray(0))
        remoteExit(out)
    }

    // ---------- Страница ----------

    private fun sendPage(out: OutputStream, bmp: Bitmap, x: Int, y: Int) {
        cmd(out, 'p', "sttp", ByteArray(0))
        cmd(out, 'p', "setn", byteArrayOf(1))

        val w = bmp.width
        val row = IntArray(w)
        val rgb = ByteArray(w * 3)
        for (ry in 0 until bmp.height) {
            val py = y + ry
            if (py < 0 || py >= PRINTABLE_H) continue
            bmp.getPixels(row, 0, w, 0, ry, w, 1)
            // Белые строки не отправляем вовсе, у остальных — только участок от первой до последней цветной точки.
            var a = 0
            while (a < w && (row[a] and 0xFFFFFF) == 0xFFFFFF) a++
            if (a == w) continue
            var b = w - 1
            while ((row[b] and 0xFFFFFF) == 0xFFFFFF) b--
            val px = x + a
            if (px >= PRINTABLE_W) continue
            b = minOf(b, PRINTABLE_W - 1 - x)
            var n = 0
            for (i in a..b) {
                val c = row[i]
                rgb[n++] = (c shr 16).toByte()
                rgb[n++] = (c shr 8).toByte()
                rgb[n++] = c.toByte()
            }
            // dsnd: x, y (2 байта, big-endian), сжатие 0, длина строки в байтах, данные RGB
            val d = ByteArrayOutputStream(7 + n)
            d.u16(px); d.u16(py); d.write(0); d.u16(n)
            d.write(rgb, 0, n)
            cmd(out, 'd', "dsnd", d.toByteArray())
        }

        cmd(out, 'p', "endp", byteArrayOf(0)) // 0 — страниц больше нет
    }

    // ---------- Кадры команд ----------

    /** ESC + буква + длина (4 байта, little-endian) + имя из 4 букв + параметры. */
    private fun cmd(out: OutputStream, letter: Char, name: String, data: ByteArray) {
        val n = data.size
        out.write(byteArrayOf(0x1B, letter.code.toByte(), n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
        out.write(name.toByteArray(Charsets.US_ASCII))
        out.write(data)
    }

    private fun remoteEnter(out: OutputStream) =
        out.write(byteArrayOf(0x1B, '('.code.toByte(), 'R'.code.toByte(), 0x08, 0x00, 0x00) + "REMOTE1".toByteArray(Charsets.US_ASCII))

    private fun remoteExit(out: OutputStream) = out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))

    /** Команда REMOTE1: 2 буквы + длина (2 байта, little-endian) + 0 + данные. */
    private fun remote(out: OutputStream, name: String, data: ByteArray) {
        val n = data.size + 1
        out.write(name.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), 0))
        out.write(data)
    }

    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
}
