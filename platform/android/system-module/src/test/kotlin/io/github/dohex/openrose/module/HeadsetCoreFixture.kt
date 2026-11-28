package io.github.dohex.openrose.module

import android.os.Binder
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.SimulatedDrivers
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.RuntimeEnvironment
import uniffi.openrose_control.ControlSnapshot
import kotlin.test.assertNotNull
import kotlin.test.fail

internal fun coreRuntime(
    scope: CoroutineScope,
    settings: UserSettings,
    platform: PlatformState,
    sessionIdBase: Long,
    drivers: SimulatedDrivers = idleDrivers(),
): CoreControlRuntime = CoreControlRuntime(
    transport = ControlTransportAdapter(drivers),
    initialPlatformState = platform,
    initialSettings = settings,
    scope = scope,
    timing = drivers.fastTiming(),
    sessionIdBase = sessionIdBase,
    clock = drivers.fixedClock(),
)

internal fun idleDrivers(): SimulatedDrivers = SimulatedDrivers().apply {
    audioConnected = false
    pairedDevices.clear()
}

internal suspend fun awaitCondition(description: String, check: () -> Boolean) {
    withTimeoutOrNull(3_000) {
        while (!check()) delay(5)
    } ?: fail("timed out waiting for $description")
}

internal class ReadyHeadset(val frame: Bundle, val hostState: ControlSnapshot)

internal suspend fun readyHeadset(): ReadyHeadset {
    val target = BridgeTarget.BLUETOOTH
    val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val drivers = SimulatedDrivers()
        lateinit var runtime: CoreControlRuntime
        val host = ModuleControlHost(
            target.packageName,
            target.packageName,
            "test-host",
            { ClientRole.HOST_PRESENTATION },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base, drivers).also { runtime = it } },
            hostScope,
            { "lease" },
        )
        host.updatePlatform(
            PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
                userUnlocked = true,
                scanPermission = PermissionState.GRANTED,
            ),
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), hostScope)
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(target.packageName, target), Binder()).getString(BridgeWire.LEASE_ID))
        awaitCondition("headset session ready") { endpoint.currentSession(lease).getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK }
        var frame: Bundle? = null
        awaitCondition("battery and noise mode to be read") {
            val request = endpoint.currentSession(lease).apply { putString(BridgeWire.LEASE_ID, lease) }
            frame = endpoint.read(request).getBundle(BridgeWire.SNAPSHOT)
            (decodeNativeSnapshot(frame ?: return@awaitCondition false) as? NativeSnapshotDecode.Decoded)?.native?.let { native ->
                native.fields.values.none { it is FieldReading.Loading }
            } == true
        }
        return ReadyHeadset(assertNotNull(frame), runtime.coreState.value)
    } finally {
        hostScope.cancel()
    }
}

internal suspend fun readyHeadsetFrame(): Bundle = readyHeadset().frame
