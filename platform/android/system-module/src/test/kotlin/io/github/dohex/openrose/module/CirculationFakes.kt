package io.github.dohex.openrose.module

import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.ffi.CirculationNodeSetup
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import uniffi.openrose_ffi.CirculationNode
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.LocalAudio
import uniffi.openrose_ffi.NearbySink
import uniffi.openrose_ffi.OwnerActive
import uniffi.openrose_ffi.PeerDiscovery
import uniffi.openrose_ffi.PeerRoute
import uniffi.openrose_ffi.PeerRouteDialer
import java.nio.file.Files

internal class SilentDiscovery : PeerDiscovery {
    override suspend fun start(peerId: String, name: String, port: UShort, wireVersion: UInt, sink: NearbySink) = Unit

    override suspend fun stop() = Unit
}

internal class IdleAudio : LocalAudio {
    override suspend fun isConnected(address: String): Boolean = false

    override suspend fun isMediaConnected(address: String): Boolean = false

    override suspend fun yieldAudio(address: String) = Unit

    override suspend fun acquire(address: String) = Unit
}

internal class UnreachableDialer : PeerRouteDialer {
    override fun openRoute(address: String, port: UShort): PeerRoute = throw DriverException.Terminal("unreachable")
}

internal class AlwaysActive : OwnerActive {
    override fun isActive(): Boolean = true
}

internal fun CoreControlRuntime.isolatedCirculationNode(): CirculationNode {
    val dir = Files.createTempDirectory("openrose-circulation").toFile().apply { deleteOnExit() }
    return circulationNode(CirculationNodeSetup(dir.path, "Phone", 0u, SilentDiscovery(), IdleAudio(), UnreachableDialer(), AlwaysActive()))
}

internal suspend fun hostedCoreRuntime(scope: CoroutineScope): CoreControlRuntime {
    lateinit var runtime: CoreControlRuntime
    val host = ModuleControlHost(
        "bluetooth",
        "bluetooth",
        "host",
        { ClientRole.APP },
        { runtimeScope, settings, platform, base -> coreRuntime(runtimeScope, settings, platform, base).also { runtime = it } },
        scope,
        { "lease" },
    )
    host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
    return runtime
}
