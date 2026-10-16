package ru.home.weatherprint

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Домашняя Wi-Fi-сеть: адрес телефона, роутер, через какую сеть ходить к принтеру. */
object LocalNet {

    /**
     * Через какую сеть обращаться к устройствам дома. Без VPN — явно через Wi-Fi.
     * С VPN — обычным путём (null): Android запрещает приложениям обходить VPN (ошибка EPERM),
     * а VPN-клиенты, как правило, и так пускают локальную сеть мимо туннеля.
     */
    fun route(ctx: Context): Network? = if (vpnActive(ctx)) null else wifi(ctx)

    fun wifi(ctx: Context): Network? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /** Свой IPv4-адрес в Wi-Fi и длина префикса подсети. */
    fun ownAddress(ctx: Context, network: Network?): Pair<Inet4Address, Int>? {
        network ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return (la.address as Inet4Address) to la.prefixLength
    }

    /** Адрес роутера (шлюз маршрута по умолчанию) в Wi-Fi. */
    fun gateway(ctx: Context, network: Network?): String? {
        network ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(network) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    /** Понятная причина, почему принтер не отвечает. */
    fun offlineReason(ctx: Context, printerHost: String): String {
        val wifi = wifi(ctx) ?: return "Телефон не подключён к Wi-Fi"
        val (own, prefix) = ownAddress(ctx, wifi) ?: return "Телефон не в домашней сети"
        val ip = runCatching { java.net.InetAddress.getByName(printerHost.trim('[', ']')) }.getOrNull() as? Inet4Address
            ?: return "Не отвечает — проверьте, что принтер включён"
        val bits = prefix.coerceIn(8, 30)
        val mask = -1 shl (32 - bits)
        fun Inet4Address.int() = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        return if ((own.int() and mask) != (ip.int() and mask)) "Телефон не в домашней сети"
        else "Не отвечает — проверьте, что принтер включён"
    }

    fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
}
