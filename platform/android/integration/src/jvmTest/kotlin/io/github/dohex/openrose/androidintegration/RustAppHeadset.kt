package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.RawAudioEvent
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.SimulatedDrivers
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_headset.ModelProfile
import java.util.concurrent.atomic.AtomicInteger

internal actual fun newAppHeadset(test: TestScope, headset: SharedHeadset, earphones: DeviceCandidate): AppHeadset = RustAppHeadset(test, headset, earphones)

internal actual fun blockFor(millis: Long) = Thread.sleep(millis)

private class RustAppHeadset(private val test: TestScope, private val shared: SharedHeadset, earphones: DeviceCandidate) : AppHeadset {
    private val drivers = SimulatedDrivers(ModelProfile.valueOf(requireNotNull(earphones.profile).name), earphones.deviceId.value).also {
        it.audioConnected = false
    }
    private val closed = AtomicInteger(0)
    private val refused = AtomicInteger(0)

    @Volatile override var closeFails = false

    @Volatile override var closeDelayMillis = 0L

    override var audioConnected: Boolean
        get() = drivers.audioConnected
        set(value) {
            drivers.audioConnected = value
            drivers.audioEvents.trySend(RawAudioEvent.ProfileChanged(AudioProfile.A2DP, drivers.pairedDevices.first(), bonded = true, connected = value))
        }
    override val connections: Int get() = drivers.gatts.size
    override val refusedReleases: Int get() = refused.get()
    override val writes: Int get() = drivers.gatts.sumOf { it.writes.size }
    override val closedRuntimes: Int get() = closed.get()

    override fun runtime(scope: CoroutineScope, settings: UserSettings, platform: PlatformState, sessionIdBase: Long): ControlRuntime {
        val core = CoreControlRuntime(
            transport = CountedTransport(ControlTransportAdapter(SharedDrivers(drivers, shared, OWNER, { closeDelayMillis }, { closeFails })), closed),
            initialPlatformState = platform,
            initialSettings = settings,
            scope = scope,
            timing = drivers.fastTiming(),
            sessionIdBase = sessionIdBase,
            clock = drivers.fixedClock(),
        )
        return CoreAwaitedInRealTime(test, core, onRelease = { released -> if (!released) refused.incrementAndGet() })
    }

    override fun close() = drivers.close()
}

private const val OWNER = "app"

private class CountedTransport(private val delegate: ControlTransportAdapter, private val closed: AtomicInteger) :
    ControlTransport by delegate,
    AutoCloseable {
    override fun close() {
        closed.incrementAndGet()
        delegate.close()
    }
}
