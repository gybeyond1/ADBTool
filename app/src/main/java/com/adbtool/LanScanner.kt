package com.adbtool

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object LanScanner {

    fun getLocalIp(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getSubnet(localIp: String): String {
        val parts = localIp.split(".")
        if (parts.size != 4) return "192.168.1"
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    fun scan(
        context: Context,
        port: Int = 5555,
        timeout: Int = 500,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onFound: ((ip: String) -> Unit)? = null
    ): List<String> {
        val localIp = getLocalIp(context) ?: return emptyList()
        val subnet = getSubnet(localIp)
        val found = mutableListOf<String>()
        val counter = AtomicInteger(0)
        val total = 254

        val executor = Executors.newFixedThreadPool(50)
        (1..254).map { i ->
            executor.submit {
                val ip = "$subnet.$i"
                val reachable = try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), timeout)
                    socket.close()
                    true
                } catch (_: Exception) {
                    false
                }
                val done = counter.incrementAndGet()
                onProgress?.invoke(done, total)
                if (reachable) {
                    synchronized(found) {
                        found.add(ip)
                    }
                    onFound?.invoke(ip)
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)

        return found.sortedBy {
            val parts = it.split(".")
            parts[3].toIntOrNull() ?: 0
        }
    }
}
