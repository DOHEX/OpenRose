package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.RawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import io.github.dohex.openrose.ffi.drivers.SimulatedDrivers
import io.github.dohex.openrose.ffi.drivers.SimulatedGatt
import io.github.dohex.openrose.ffi.kotlincore.CoreBackedRuntime
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import uniffi.openrose_headset.ModelProfile
import java.util.concurrent.CopyOnWriteArrayList

internal actual fun newHostHeadset(
    test: TestScope,
    headset: SharedHeadset,
    earphones: DeviceCandidate,
    bonded: List<DeviceCandidate>,
    rewriteReply: ((String) -> String)?,
): HostHeadset = RustHostHeadset(test, headset, earphones, bonded, rewriteReply)

private const val OWNER = "module"

private class RustHostHeadset(
    private val test: TestScope,
    private val shared: SharedHeadset,
    earphones: DeviceCandidate,
    bonded: List<DeviceCandidate>,
    rewriteReply: ((String) -> String)?,
) : HostHeadset {
    private val profile = ModelProfile.valueOf(requireNotNull(earphones.profile).name)
    private val drivers = SimulatedDrivers(profile, earphones.deviceId.value).also { simulated ->
        simulated.audioConnected = false
        bonded.filter { it.deviceId != earphones.deviceId }.forEach {
            simulated.pairedDevices.add(RawAudioDevice(it.displayName(), it.deviceId.value, null))
        }
    }
    private val scripted = rewriteReply?.let { ScriptedDrivers(drivers, test, profile, drivers.device, it) }
    private val platformDrivers: PlatformDrivers = scripted ?: drivers
    private val runtimes = CopyOnWriteArrayList<CoreBackedRuntime>()

    override val links: List<HostLink> get() = scripted?.links ?: drivers.gatts.map { SimulatedHostLink(test, it) }

    override fun connectAudio(deviceId: DeviceId) {
        drivers.audioConnected = true
        drivers.audioEvents.trySend(RawAudioEvent.ProfileChanged(AudioProfile.A2DP, drivers.pairedDevices.first { it.address == deviceId.value }, bonded = true, connected = true))
        test.quiesce()
    }

    override fun runtime(scope: CoroutineScope, settings: UserSettings, platform: PlatformState, sessionIdBase: Long): CoreBackedRuntime {
        val core = CoreControlRuntime(
            transport = ControlTransportAdapter(SharedDrivers(platformDrivers, shared, OWNER)),
            initialPlatformState = platform,
            initialSettings = settings,
            scope = scope,
            timing = drivers.fastTiming(),
            sessionIdBase = sessionIdBase,
            clock = drivers.fixedClock(),
        )
        lateinit var awaited: CoreBackedRuntime
        awaited = CoreAwaitedInRealTime(test, core, onRelease = { released -> if (released) runtimes.remove(awaited) }, replyWithheld = { links.any { !it.initializing } })
        return awaited.also(runtimes::add)
    }

    override suspend fun endProcess() {
        runtimes.forEach { it.release() }
        runtimes.clear()
    }

    override fun close() = drivers.close()
}

private class SimulatedHostLink(private val test: TestScope, private val gatt: SimulatedGatt) : HostLink {
    override val writes: List<String> get() = gatt.writes.toList()
    override val closed: Boolean get() = gatt.closes.isNotEmpty()
    override var initializing: Boolean
        get() = !gatt.muted
        set(value) {
            gatt.muted = !value
        }

    override fun deviceReply(hex: String) {
        test.quiesce()
        gatt.inject(hex)
        test.quiesce()
    }
}
