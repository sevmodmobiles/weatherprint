package ru.home.weatherprint

import android.content.Context
import android.net.ConnectivityManager
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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

    private const val TYPE = "_ipp._tcp."
    private const val IPP_PORT = 631
    private val IPP_PATHS = listOf("/ipp/print", "/ipp/printer", "/ipp", "/")

    suspend fun discover(ctx: Context, seconds: Int = 6): List<Found> = coroutineScope {
        val mdns = async { runCatching { mdns(ctx, seconds) }.getOrDefault(emptyList()) }
        val scan = async { runCatching { scanSubnet(ctx) }.getOrDefault(emptyList()) }
        val byMdns = mdns.await()
        // Если принтер виден и так и так — оставляем запись из mDNS: у неё стабильное имя службы.
        val known = byMdns.map { it.host }.toSet()
        byMdns + scan.await().filter { it.host !in known }
    }

    /** Ищет ранее выбранный принтер — чтобы найти его заново, если сменился IP. */
    suspend fun findByName(ctx: Context, name: String): Found? {
        val list = discover(ctx, seconds = 4)
        // Найденный перебором подсети принтер может называться иначе, чем по mDNS.
        // Если принтер в сети один — это он.
        return list.firstOrNull { it.name == name } ?: list.singleOrNull()
    }

    // ---------- mDNS ----------

    private suspend fun mdns(ctx: Context, seconds: Int): List<Found> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val services = mutableListOf<NsdServiceInfo>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(services) {
                    if (services.none { it.serviceName == info.serviceName }) services.add(info)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }

        // Без этого многие телефоны в режиме энергосбережения Wi-Fi отбрасывают multicast-пакеты.
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = runCatching {
            wifi.createMulticastLock("weatherprint").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            try {
                delay(seconds * 1000L)
            } finally {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }

            val found = mutableListOf<Found>()
            for (s in synchronized(services) { services.toList() }) {
                // Разрешаем по одному: NsdManager не любит параллельные запросы.
                val resolved = withTimeoutOrNull(4000) { resolve(nsd, s) } ?: continue
                val addr = pickAddress(resolved) ?: continue
                val txt = resolved.attributes
                val rp = txt["rp"]?.let { String(it) } ?: "ipp/print"
                val model = txt["ty"]?.let { String(it) } ?: resolved.serviceName
                found.add(Found(resolved.serviceName, addr, "/" + rp.trimStart('/'), model))
            }
            return found
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

    /** Адреса /24-подсети, в которой сейчас телефон (кроме своего). */
    private fun subnetHosts(ctx: Context): List<String> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        val own = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address } ?: return emptyList()
        val b = own.address
        val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val self = b[3].toInt() and 0xFF
        return (1..254).filter { it != self }.map { prefix + it }
    }

    private suspend fun scanSubnet(ctx: Context): List<Found> = withContext(Dispatchers.IO) {
        val limit = Semaphore(48)
        val open = subnetHosts(ctx).map { ip ->
            async { limit.withPermit { if (portOpen(ip, IPP_PORT, 700)) ip else null } }
        }.awaitAll().filterNotNull()

        open.map { ip -> async { probe(ip) } }.awaitAll().filterNotNull()
    }

    private fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) } }.isSuccess

    /** Спрашивает у узла атрибуты IPP по типичным путям. Ответил — значит, это принтер. */
    private fun probe(ip: String): Found? {
        for (path in IPP_PATHS) {
            val attrs = runCatching { IppClient(ip, path).getAttributes(timeoutMs = 3000) }.getOrNull() ?: continue
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
