package ru.home.weatherprint

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Поиск сетевых принтеров по mDNS/Bonjour (служба _ipp._tcp) — так же их находят AirPrint и Mopria.
 * Разрешение на геолокацию не нужно.
 */
object PrinterDiscovery {

    data class Found(val name: String, val host: String, val path: String, val model: String)

    private const val TYPE = "_ipp._tcp."

    suspend fun discover(ctx: Context, seconds: Int = 5): List<Found> {
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
    }

    /** Ищет принтер с конкретным именем службы — чтобы найти его заново, если сменился IP. */
    suspend fun findByName(ctx: Context, name: String): Found? =
        discover(ctx, seconds = 4).firstOrNull { it.name == name }

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
}
