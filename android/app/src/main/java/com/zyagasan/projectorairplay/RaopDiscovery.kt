package com.zyagasan.projectorairplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.ArrayDeque

/** AirPlay(RAOP)送信先。name は "MACアドレス@表示名" の形式。 */
data class Target(val serviceName: String, val host: String, val port: Int, val et: String?) {
    val displayName: String get() = serviceName.substringAfter('@', serviceName)
}

/** mDNS(_raop._tcp)で AirPlay 機器を探す。 */
class RaopDiscovery(ctx: Context, private val onChange: (List<Target>) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Target>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        lock = wifi.createMulticastLock("raop-discovery").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                synchronized(pending) { pending.add(s) }
                main.post { resolveNext() }
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                found.remove(s.serviceName)
                main.post { onChange(found.values.sortedBy { it.displayName }) }
            }
        }
        listener = l
        nsd.discoverServices("_raop._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val s = synchronized(pending) { pending.poll() } ?: return
        resolving = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val host = i.host
                if (host is Inet4Address) {
                    val et = i.attributes["et"]?.let { String(it) }
                    found[i.serviceName] = Target(i.serviceName, host.hostAddress!!, i.port, et)
                    main.post { onChange(found.values.sortedBy { it.displayName }) }
                }
                done()
            }
            private fun done() { resolving = false; main.post { resolveNext() } }
        })
    }
}
