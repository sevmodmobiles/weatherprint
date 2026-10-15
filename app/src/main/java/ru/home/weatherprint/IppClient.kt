package ru.home.weatherprint

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Минимальный клиент IPP (тот же протокол, что у AirPrint/Mopria).
 * Печать идёт напрямую по сети, без системного диалога.
 */
class IppClient(private val host: String, private val path: String) {

    class IppException(msg: String) : Exception(msg)

    private val printerUri get() = "ipp://$host:631$path"
    private val httpUrl get() = "http://$host:631$path"

    /** Запрашивает атрибуты принтера. Заодно служит проверкой «принтер в сети = я дома». */
    fun getAttributes(timeoutMs: Int = 4000): Map<String, List<String>> {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        header(out, op = 0x000B)
        operationAttrs(out)
        attr(out, 0x44, "requested-attributes", "printer-make-and-model")
        attr(out, 0x44, "", "document-format-supported")
        attr(out, 0x44, "", "printer-state")
        attr(out, 0x44, "", "media-supported")
        attr(out, 0x44, "", "printer-name")
        attr(out, 0x44, "", "printer-info")
        out.writeByte(0x03)
        val resp = post(body.toByteArray(), timeoutMs)
        checkStatus(resp)
        return parse(resp)
    }

    /** Отправляет задание печати. */
    fun printJob(document: ByteArray, mime: String, jobName: String) {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        header(out, op = 0x0002)
        operationAttrs(out)
        attr(out, 0x42, "job-name", jobName)
        attr(out, 0x49, "document-format", mime)
        out.writeByte(0x02) // job-attributes-tag
        attr(out, 0x44, "media", "iso_a4_210x297mm")
        attr(out, 0x44, "print-color-mode", "color")
        attr(out, 0x44, "print-scaling", "fit")
        attrInt(out, 0x23, "print-quality", 4) // normal
        out.writeByte(0x03)
        out.write(document)
        val resp = post(body.toByteArray(), 30_000)
        checkStatus(resp)
    }

    private fun header(out: DataOutputStream, op: Int) {
        out.writeByte(0x01); out.writeByte(0x01)       // IPP 1.1
        out.writeShort(op)
        out.writeInt(Random.nextInt(1, Int.MAX_VALUE))  // request-id
        out.writeByte(0x01)                             // operation-attributes-tag
    }

    private fun operationAttrs(out: DataOutputStream) {
        attr(out, 0x47, "attributes-charset", "utf-8")
        attr(out, 0x48, "attributes-natural-language", "ru")
        attr(out, 0x45, "printer-uri", printerUri)
        attr(out, 0x42, "requesting-user-name", "weatherprint")
    }

    private fun attr(out: DataOutputStream, tag: Int, name: String, value: String) {
        val n = name.toByteArray(Charsets.US_ASCII)
        val v = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(n.size); out.write(n)
        out.writeShort(v.size); out.write(v)
    }

    private fun attrInt(out: DataOutputStream, tag: Int, name: String, value: Int) {
        val n = name.toByteArray(Charsets.US_ASCII)
        out.writeByte(tag)
        out.writeShort(n.size); out.write(n)
        out.writeShort(4); out.writeInt(value)
    }

    private fun post(body: ByteArray, timeoutMs: Int): ByteArray {
        val conn = URL(httpUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = maxOf(timeoutMs, 15_000)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IppException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun checkStatus(resp: ByteArray) {
        if (resp.size < 8) throw IppException("Пустой ответ принтера")
        val status = u16(resp, 2)
        if (status >= 0x0100) throw IppException("Принтер вернул ошибку 0x%04X".format(status))
    }

    private fun parse(b: ByteArray): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var i = 8
        var current = ""
        while (i < b.size) {
            val tag = b[i].toInt() and 0xFF
            if (tag <= 0x0F) {           // разделитель группы
                i++
                if (tag == 0x03) break
                continue
            }
            if (i + 3 > b.size) break
            val nLen = u16(b, i + 1)
            val name = String(b, i + 3, nLen, Charsets.US_ASCII)
            val vPos = i + 3 + nLen
            val vLen = u16(b, vPos)
            val value = b.copyOfRange(vPos + 2, vPos + 2 + vLen)
            if (name.isNotEmpty()) current = name
            val text = when (tag) {
                0x21, 0x23 -> if (vLen == 4) java.nio.ByteBuffer.wrap(value).int.toString() else ""
                in 0x40..0x4F -> String(value, Charsets.UTF_8)
                else -> ""
            }
            if (text.isNotEmpty()) result.getOrPut(current) { mutableListOf() }.add(text)
            i = vPos + 2 + vLen
        }
        return result
    }

    private fun u16(b: ByteArray, pos: Int) =
        ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
}
