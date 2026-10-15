package ru.home.weatherprint

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import javax.net.SocketFactory
import kotlin.coroutines.resume

/**
 * Поиск сетевых принтеров. Два способа параллельно:
 * 1. mDNS/Bonjour (служба _ipp._tcp) — так же их находят AirPrint и Mopria.
 * 2. Перебор адресов своей подсети на порту 631 (IPP). Нужен, когда роутер не пропускает
 *    multicast — например, принтер в Wi-Fi 2.4 ГГц, а телефон в 5 ГГц.
 * Разрешение на геолокацию не нужно.
 */
object PrinterDiscovery {

    data class Found(val name: String, val host: String, val path: String, val model: String)

    /** Найденные принтеры и отчёт о поиске — чтобы было понятно, почему ничего не нашлось. */
    data class Result(val found: List<Found>, val report: String)

    private const val TYPE = "_ipp._tcp."
    private const val IPP_PORT = 631
    private val IPP_PATHS = listOf("/ipp/print", "/ipp/printer", "/ipp", "/")

    suspend fun discover(ctx: Context, seconds: Int = 6): Result = coroutineScope {
        val lan = LocalNet.wifi(ctx)
        val mdns = async { runCatching { mdns(ctx, lan, seconds) }.getOrElse { MdnsResult(emptyList(), "ошибка ${it.message}") } }
        val scan = async { runCatching { scanSubnet(ctx, lan) }.getOrElse { ScanResult(emptyList(), "ошибка ${it.message}") } }
        val m = mdns.await()
        val s = scan.await()
        // Если принтер виден и так и так — оставляем запись из mDNS: у неё стабильное имя службы.
        val known = m.found.map { it.host }.toSet()
        val found = m.found + s.found.filter { it.host !in known }
        val report = buildString {
            if (lan == null) append("Wi-Fi-сеть не найдена.\n")
            append("Bonjour: ${m.report}\n")
            append(s.report)
        }
        Result(found, report)
    }

    /** Ищет ранее выбранный принтер — чтобы найти его заново, если сменился IP. */
    suspend fun findByName(ctx: Context, name: String): Found? {
        val list = discover(ctx, seconds = 4).found
        // Найденный перебором подсети принтер может называться иначе, чем по mDNS.
        // Если принтер в сети один — это он.
        return list.firstOrNull { it.name == name } ?: list.singleOrNull()
    }

    // ---------- mDNS ----------

    private class MdnsResult(val found: List<Found>, val report: String)

    private suspend fun mdns(ctx: Context, lan: Network?, seconds: Int): MdnsResult {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val services = mutableListOf<NsdServiceInfo>()
        var startError: Int? = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(services) {
                    if (services.none { it.serviceName == info.serviceName }) services.add(info)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { startError = code }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }

        // Без этого многие телефоны в режиме энергосбережения Wi-Fi отбрасывают multicast-пакеты.
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = runCatching {
            wifi.createMulticastLock("weatherprint").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        try {
            if (Build.VERSION.SDK_INT >= 33 && lan != null) {
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, lan, { it.run() }, listener)
            } else {
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
            try {
                delay(seconds * 1000L)
            } finally {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }

            val all = synchronized(services) { services.toList() }
            val found = mutableListOf<Found>()
            for (s in all) {
                // Разрешаем по одному: NsdManager не любит параллельные запросы.
                val resolved = withTimeoutOrNull(4000) { resolve(nsd, s) } ?: continue
                val addr = pickAddress(resolved) ?: continue
                val txt = resolved.attributes
                val rp = txt["rp"]?.let { String(it) } ?: "ipp/print"
                val model = txt["ty"]?.let { String(it) } ?: resolved.serviceName
                found.add(Found(resolved.serviceName, addr, "/" + rp.trimStart('/'), model))
            }
            val report = when {
                startError != null -> "не запустился (код $startError)"
                all.isEmpty() -> "никто не ответил"
                else -> "ответили ${all.size}: " + all.joinToString { it.serviceName } +
                    (if (found.size < all.size) " (адрес узнать не удалось у ${all.size - found.size})" else "")
            }
            return MdnsResult(found, report)
        } finally {
            runCatching { lock?.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(si: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(si)
                }
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }

    @Suppress("DEPRECATION")
    private fun pickAddress(si: NsdServiceInfo): String? {
        val all: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)
        return (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())
            ?.hostAddress
            ?.let { if (it.contains(':')) "[${it.substringBefore('%')}]" else it }
    }

    // ---------- Перебор подсети ----------

    private class ScanResult(val found: List<Found>, val report: String)

    private enum class Port { OPEN, REFUSED, SILENT }

    private suspend fun scanSubnet(ctx: Context, lan: Network?): ScanResult = withContext(Dispatchers.IO) {
        val (own, prefixLen) = LocalNet.ownAddress(ctx, lan)
            ?: return@withContext ScanResult(emptyList(), "Адрес телефона в Wi-Fi не определён — перебор не выполнен.")
        val b = own.address
        val net = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val self = b[3].toInt() and 0xFF
        val hosts = (1..254).filter { it != self }.map { net + it }
        val sf = lan?.socketFactory ?: SocketFactory.getDefault()

        // Порт 631 — IPP, 9100 — «сырая» печать, 80 — веб-интерфейс (у Epson он есть всегда).
        val ports = listOf(IPP_PORT, 9100, 80)
        val limit = Semaphore(96)
        val states: Map<String, Map<Int, Port>> = hosts.map { ip ->
            async {
                ip to ports.associateWith { port -> limit.withPermit { check(sf, ip, port, 800) } }
            }
        }.awaitAll().toMap()

        val alive = states.filterValues { m -> m.values.any { it != Port.SILENT } }.keys
        val ipp = states.filterValues { it[IPP_PORT] == Port.OPEN }.keys
        val found = ipp.map { ip -> async { probe(ip, lan) } }.awaitAll().filterNotNull()

        val report = buildString {
            append("Телефон: ${own.hostAddress}/$prefixLen. Проверены адреса ${net}1–254.\n")
            append("Отвечают устройств: ${alive.size}")
            if (alive.isNotEmpty()) append(" (${alive.sortedBy { it.substringAfterLast('.').toInt() }.joinToString { it.substringAfterLast('.') }})")
            append(".\n")
            append("Порт 631 (IPP) открыт: ${if (ipp.isEmpty()) "ни у кого" else ipp.joinToString()}.\n")
            val raw = states.filterValues { it[9100] == Port.OPEN }.keys - ipp
            if (raw.isNotEmpty()) append("Порт 9100 (печать) открыт: ${raw.joinToString()}.\n")
            val epsonWeb = (states.filterValues { it[80] == Port.OPEN }.keys - ipp)
                .filter { looksLikeEpson(it, lan) }
            if (epsonWeb.isNotEmpty()) append("Веб-интерфейс Epson: ${epsonWeb.joinToString()} — принтер есть, но IPP не отвечает.\n")
            if (alive.size <= 1) append("Телефон почти никого не видит — похоже, в роутере включена изоляция клиентов (или это гостевая сеть).\n")
        }.trimEnd()
        ScanResult(found, report)
    }

    private fun check(sf: SocketFactory, ip: String, port: Int, timeoutMs: Int): Port =
        try {
            sf.createSocket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            Port.OPEN
        } catch (e: ConnectException) {
            // «Connection refused» — узел есть, просто порт закрыт.
            if (e.message.orEmpty().contains("refused", ignoreCase = true)) Port.REFUSED else Port.SILENT
        } catch (e: Exception) {
            Port.SILENT
        }

    private fun looksLikeEpson(ip: String, lan: Network?): Boolean = runCatching {
        val url = URL("http://$ip/")
        val conn = (lan?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() }.orEmpty()
            text.contains("EPSON", ignoreCase = true) || conn.getHeaderField("Server").orEmpty().contains("EPSON", true)
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** Спрашивает у узла атрибуты IPP по типичным путям. Ответил — значит, это принтер. */
    private fun probe(ip: String, lan: Network?): Found? {
        for (path in IPP_PATHS) {
            val attrs = runCatching { IppClient(ip, path, lan).getAttributes(timeoutMs = 3000) }.getOrNull() ?: continue
            val model = attrs["printer-make-and-model"]?.firstOrNull()
            val name = attrs["printer-name"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: attrs["printer-info"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: model
                ?: ip
            return Found(name, ip, path, model ?: name)
        }
        return null
    }
}
