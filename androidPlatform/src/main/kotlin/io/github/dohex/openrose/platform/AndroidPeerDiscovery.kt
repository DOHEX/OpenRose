package io.github.dohex.openrose.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import io.github.dohex.openrose.circulation.NearbyPeer
import io.github.dohex.openrose.circulation.PeerDiscovery

/** Android NSD owns the multicast socket. Serialize resolutions for API 26–33, where only one may run at once. */
@Suppress("DEPRECATION")
class AndroidPeerDiscovery(context: Context) : PeerDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var active = false
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableMapOf<String, NearbyPeer>()
    private var resolving = false

    override fun start(id: String, name: String, port: Int, found: (List<NearbyPeer>) -> Unit) {
        main.post {
            active = true
            val own = NsdServiceInfo().apply {
                serviceName = PeerDiscovery.serviceName(id)
                serviceType = PeerDiscovery.TYPE
                this.port = port
                setAttribute("id", id)
                setAttribute("name", name.take(60))
            }
            register(own)
            discover(id, found)
        }
    }

    private fun register(own: NsdServiceInfo) {
        if (!active) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = retry { register(own) }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(own, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { retry { register(own) } }
    }

    private fun discover(id: String, found: (List<NearbyPeer>) -> Unit) {
        if (!active) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) = retry { discover(id, found) }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (!active || info.serviceName == PeerDiscovery.serviceName(id)) return@post
                    services[info.serviceName] = info
                    pending.add(info)
                    resolve(id, found)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    services.remove(info.serviceName)
                    seen.remove(info.serviceName)
                    found(seen.values.toList())
                }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(PeerDiscovery.TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { retry { discover(id, found) } }
    }

    private fun resolve(id: String, found: (List<NearbyPeer>) -> Unit) {
        if (resolving || !active) return
        val next = pending.removeFirstOrNull() ?: return
        if (services[next.serviceName] !== next) {
            main.post { resolve(id, found) }
            return
        }
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                main.post {
                    resolving = false
                    retry {
                        if (services[next.serviceName] === next) {
                            pending.add(next)
                            resolve(id, found)
                        }
                    }
                    resolve(id, found)
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (active && services[next.serviceName] === next) {
                        info.peer()?.takeUnless { it.id == id }?.let {
                            seen[next.serviceName] = it
                            found(seen.values.toList())
                        }
                    }
                    resolve(id, found)
                }
            }
        }
        runCatching { nsd.resolveService(next, listener) }.onFailure { listener.onResolveFailed(next, -1) }
    }

    private fun NsdServiceInfo.peer(): NearbyPeer? {
        val peerId = attributes["id"]?.decodeToString() ?: return null
        val displayName = attributes["name"]?.decodeToString() ?: return null
        return NearbyPeer(peerId, displayName, host ?: return null, port)
    }

    private fun retry(action: () -> Unit) {
        main.postDelayed({ if (active) action() }, 3_000L)
    }

    override fun close() {
        main.post {
            active = false
            main.removeCallbacksAndMessages(null)
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            registration?.let { runCatching { nsd.unregisterService(it) } }
            services.clear()
            seen.clear()
            pending.clear()
        }
    }
}
