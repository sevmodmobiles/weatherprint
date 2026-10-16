package ru.home.weatherprint

import android.net.Network
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Состояние принтера по SNMP (UDP 161, сообщество «public»).
 *
 * Epson отдаёт подробный статус через свою ветку MIB — команда «st» возвращает пакет «@BDC ST2»,
 * в котором есть код состояния: 4 — готов, 7 — идёт прочистка, 2 — занят и т. д. (так читают статус
 * утилиты Epson и открытые проекты вроде epson_print_conf). Для остальных принтеров есть
 * стандартный hrPrinterStatus из Printer-MIB: 3 — готов, 4 — печатает, 5 — прогрев.
 */
object PrinterStatus {

    enum class State { IDLE, BUSY, UNKNOWN }

    private const val HR_PRINTER_STATUS = "1.3.6.1.2.1.25.3.5.1.1.1"
    private const val EPSON_ST = "1.3.6.1.4.1.1248.1.2.2.44.1.1.2.1.115.116.1.0.1" // «st», длина 1, параметр 1

    fun state(host: String, lan: Network?): State {
        epsonStatus(host, lan)?.let { return if (it == 4) State.IDLE else State.BUSY }
        hrPrinterStatus(host, lan)?.let {
            return when (it) { 3 -> State.IDLE; 4, 5 -> State.BUSY; else -> State.UNKNOWN }
        }
        return State.UNKNOWN
    }

    /** Код состояния Epson из пакета «@BDC ST2» (поле 0x01) или null. */
    fun epsonStatus(host: String, lan: Network?): Int? {
        val v = snmpGet(host, EPSON_ST, lan) ?: return null
        val marker = "@BDC ST2\r\n".toByteArray(Charsets.US_ASCII)
        val start = indexOf(v, marker).takeIf { it >= 0 } ?: return null
        var i = start + marker.size + 2 // пропускаем длину (2 байта)
        while (i + 1 < v.size) {
            val type = v[i].toInt() and 0xFF
            val len = v[i + 1].toInt() and 0xFF
            if (type == 0x01 && len >= 1 && i + 2 < v.size) return v[i + 2].toInt() and 0xFF
            i += 2 + len
        }
        return null
    }

    fun hrPrinterStatus(host: String, lan: Network?): Int? {
        val v = snmpGet(host, HR_PRINTER_STATUS, lan) ?: return null
        if (v.isEmpty() || v.size > 4) return null
        return v.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    }

    /**
     * Ждёт окончания операции (прочистки): сначала принтер должен стать занятым, потом снова готовым.
     * Возвращает true, если окончание подтвердил сам принтер, false — если статус недоступен
     * и пришлось просто выждать [fallbackMs].
     */
    suspend fun waitUntilDone(host: String, lan: Network?, fallbackMs: Long = 100_000, maxMs: Long = 300_000): Boolean {
        val start = System.currentTimeMillis()
        var sawBusy = false
        var sawAny = false
        delay(5_000)
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            val st = runCatching { state(host, lan) }.getOrDefault(State.UNKNOWN)
            if (st != State.UNKNOWN) sawAny = true
            if (st == State.BUSY) sawBusy = true
            // Готов после «занят» — или готов уже долго (прочистка могла пройти между опросами)
            if (st == State.IDLE && (sawBusy || elapsed > 40_000)) return true
            if (!sawAny && elapsed >= fallbackMs) return false
            if (elapsed >= maxMs) return false
            delay(3_000)
        }
    }

    // ---------- SNMP v1 GetRequest ----------

    private fun snmpGet(host: String, oid: String, lan: Network?): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            lan?.bindSocket(s)
            s.soTimeout = 1500
            val addr = InetAddress.getByName(host.trim('[', ']'))
            val req = request(oid, Random.nextInt(1, 0x7FFFFFFF))
            s.send(DatagramPacket(req, req.size, addr, 161))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            value(buf.copyOf(p.length))
        }
    }.getOrNull()

    private fun request(oid: String, id: Int): ByteArray {
        val varbind = tlv(0x30, tlv(0x06, encodeOid(oid)) + tlv(0x05, ByteArray(0)))
        val pdu = tlv(0xA0, int(id) + int(0) + int(0) + tlv(0x30, varbind))
        return tlv(0x30, int(0) + tlv(0x04, "public".toByteArray()) + pdu)
    }

    private fun int(v: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        var started = false
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val b = (v shr shift) and 0xFF
            if (!started && b == 0 && shift != 0) continue
            if (!started && (b and 0x80) != 0) bytes.write(0)
            started = true
            bytes.write(b)
        }
        return tlv(0x02, bytes.toByteArray())
    }

    private fun encodeOid(oid: String): ByteArray {
        val parts = oid.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (n in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = n
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            for (b in stack.reversed()) out.write(b)
        }
        return out.toByteArray()
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = body.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(body)
        return out.toByteArray()
    }

    /** Достаёт значение первой пары OID–значение из ответа; null при ошибке SNMP или «нет такого объекта». */
    private fun value(b: ByteArray): ByteArray? {
        var pos = 0
        fun read(): Triple<Int, Int, Int> { // тег, начало тела, длина
            val tag = b[pos].toInt() and 0xFF
            var len = b[pos + 1].toInt() and 0xFF
            var start = pos + 2
            if ((len and 0x80) != 0) {
                val k = len and 0x7F
                len = 0
                for (i in 0 until k) len = (len shl 8) or (b[start + i].toInt() and 0xFF)
                start += k
            }
            return Triple(tag, start, len)
        }
        fun enter() { pos = read().second }
        fun skip() { val (_, s, l) = read(); pos = s + l }

        enter()            // Message
        skip(); skip()     // version, community
        enter()            // GetResponse-PDU
        skip()             // request-id
        val (_, es, el) = read()
        if (el != 1 || b[es].toInt() != 0) return null // error-status ≠ 0
        skip(); skip()     // error-status, error-index
        enter(); enter()   // varbind list, varbind
        skip()             // OID
        val (tag, s, l) = read()
        if (tag == 0x05 || tag in 0x80..0x82) return null // NULL, noSuchObject и т. п.
        return b.copyOfRange(s, s + l)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
