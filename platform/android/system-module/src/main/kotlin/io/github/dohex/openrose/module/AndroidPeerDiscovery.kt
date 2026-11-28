package io.github.dohex.openrose.module

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.openrose_circulation.NearbyPeerRecord
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.NearbySink
import uniffi.openrose_ffi.PeerDiscovery
import uniffi.openrose_ffi.PeerRoute
import uniffi.openrose_ffi.PeerRouteDialer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

private data class RouteKey(val address: String, val port: Int)

private class RouteTarget(val address: InetAddress, val socketFactory: SocketFactory)

private class ResolvedPeer(val record: NearbyPeerRecord, val targets: Map<RouteKey, RouteTarget>)

@Suppress("DEPRECATION", "TooManyFunctions")
internal class AndroidPeerDiscovery(context: Context) :
    PeerDiscovery,
    PeerRouteDialer {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var active = false
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableMapOf<String, ResolvedPeer>()
    private val routes = ConcurrentHashMap<RouteKey, RouteTarget>()
    private var resolving = false

    override suspend fun start(peerId: String, name: String, port: UShort, wireVersion: UInt, sink: NearbySink) {
        main.post {
            active = true
            val own = NsdServiceInfo().apply {
                serviceName = serviceName(peerId)
                serviceType = SERVICE_TYPE
                this.port = port.toInt()
                setAttribute(ATTRIBUTE_ID, peerId)
                setAttribute(ATTRIBUTE_NAME, name.take(MAX_ADVERTISED_NAME))
                setAttribute(ATTRIBUTE_VERSION, wireVersion.toString())
            }
            register(own)
            discover(Listening(peerId, wireVersion, sink))
        }
    }

    private class Listening(val id: String, val wireVersion: UInt, val sink: NearbySink)

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

    private fun discover(listening: Listening) {
        if (!active) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) = retry { discover(listening) }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (!active || info.serviceName == serviceName(listening.id)) return@post
                    services[info.serviceName] = info
                    pending.add(info)
                    resolve(listening)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    services.remove(info.serviceName)
                    seen.remove(info.serviceName)
                    publish(listening)
                }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { retry { discover(listening) } }
    }

    private fun resolve(listening: Listening) {
        if (resolving || !active) return
        val next = pending.removeFirstOrNull() ?: return
        if (services[next.serviceName] !== next) {
            main.post { resolve(listening) }
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
                            resolve(listening)
                        }
                    }
                    resolve(listening)
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (active && services[next.serviceName] === next) {
                        info.resolvedPeer(listening)?.let { seen[next.serviceName] = it } ?: seen.remove(next.serviceName)
                        publish(listening)
                    }
                    resolve(listening)
                }
            }
        }
        runCatching { nsd.resolveService(next, listener) }.onFailure { listener.onResolveFailed(next, -1) }
    }

    private fun publish(listening: Listening) {
        routes.clear()
        seen.values.forEach { routes.putAll(it.targets) }
        listening.sink.publish(seen.values.map { it.record })
    }

    private fun NsdServiceInfo.resolvedPeer(listening: Listening): ResolvedPeer? {
        val peerId = attributes[ATTRIBUTE_ID]?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() && it != listening.id }
        val displayName = attributes[ATTRIBUTE_NAME]?.decodeToString()
        val version = attributes[ATTRIBUTE_VERSION]?.decodeToString()?.trim()?.toUIntOrNull()
        if (peerId == null || displayName == null || version != listening.wireVersion) return null
        val addresses = if (Build.VERSION.SDK_INT >= 34) hostAddresses else listOfNotNull(host)
        val factory = if (Build.VERSION.SDK_INT >= 33) network?.socketFactory else null
        val socketFactory = factory ?: SocketFactory.getDefault()
        val targets = addresses.associate { address ->
            RouteKey(address.hostAddress.orEmpty().substringBefore('%'), port) to RouteTarget(address, socketFactory)
        }
        val shown = displayName.take(MAX_ADVERTISED_NAME)
        val record = NearbyPeerRecord(peerId, shown, targets.keys.map { it.address }, port.toUShort())
        return ResolvedPeer(record, targets)
    }

    private fun retry(action: () -> Unit) {
        main.postDelayed({ if (active) action() }, RETRY_MILLIS)
    }

    override suspend fun stop() {
        main.post {
            active = false
            main.removeCallbacksAndMessages(null)
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            registration?.let { runCatching { nsd.unregisterService(it) } }
            services.clear()
            seen.clear()
            pending.clear()
            routes.clear()
        }
    }

    override fun openRoute(address: String, port: UShort): PeerRoute {
        val target = routes[RouteKey(address, port.toInt())]
            ?: runCatching { RouteTarget(InetAddress.getByName(address), SocketFactory.getDefault()) }
                .getOrElse { throw DriverException.Terminal("unresolvable address") }
        return AndroidPeerRoute(target, port.toInt())
    }

    private companion object {
        const val SERVICE_TYPE = "_openrose._tcp."
        const val ATTRIBUTE_ID = "id"
        const val ATTRIBUTE_NAME = "name"
        const val ATTRIBUTE_VERSION = "v"
        const val MAX_ADVERTISED_NAME = 60
        const val RETRY_MILLIS = 3_000L

        fun serviceName(id: String) = "OpenRose-${id.take(20)}"
    }
}

private fun IOException.terminal() = DriverException.Terminal(toString())

private class AndroidPeerRoute(private val target: RouteTarget, private val port: Int) : PeerRoute {
    private val socket = AtomicReference<Socket?>(null)
    private val input = AtomicReference<InputStream?>(null)
    private val output = AtomicReference<OutputStream?>(null)

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val opened = target.socketFactory.createSocket()
            try {
                opened.connect(InetSocketAddress(target.address, port), CONNECT_TIMEOUT_MILLIS)
                socket.set(opened)
                input.set(opened.getInputStream())
                output.set(opened.getOutputStream())
            } catch (failure: IOException) {
                runCatching { opened.close() }
                throw failure.terminal()
            }
        }
    }

    override suspend fun read(): ByteArray {
        val stream = input.get() ?: throw DriverException.Terminal("route closed")
        return withContext(Dispatchers.IO) {
            try {
                val buffer = ByteArray(READ_CHUNK)
                val count = stream.read(buffer)
                if (count <= 0) ByteArray(0) else buffer.copyOf(count)
            } catch (failure: IOException) {
                throw failure.terminal()
            }
        }
    }

    override suspend fun write(value: ByteArray) {
        val stream = output.get() ?: throw DriverException.Terminal("route closed")
        withContext(Dispatchers.IO) {
            try {
                stream.write(value)
                stream.flush()
            } catch (failure: IOException) {
                throw failure.terminal()
            }
        }
    }

    override suspend fun closeLink() {
        input.set(null)
        output.set(null)
        withContext(Dispatchers.IO) { socket.getAndSet(null)?.let { runCatching { it.close() } } }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_CHUNK = 4_096
    }
}
