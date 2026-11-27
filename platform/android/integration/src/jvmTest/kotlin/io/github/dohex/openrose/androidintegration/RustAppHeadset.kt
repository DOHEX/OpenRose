package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.core.transport.RawAudioEvent
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import io.github.dohex.openrose.ffi.drivers.SimulatedDrivers
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_headset.ModelProfile
import java.util.concurrent.atomic.AtomicInteger

internal actual fun newAppHeadset(test: TestScope, headset: SharedHeadset, earphones: DeviceCandidate): AppHeadset = RustAppHeadset(test, headset, earphones)

internal actual fun blockFor(millis: Long) = Thread.sleep(millis)

private const val OWNER = "app"

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
            transport = CountedTransport(ControlTransportAdapter(RefusableDrivers()), closed),
            initialPlatformState = platform,
            initialSettings = settings,
            scope = scope,
            timing = drivers.fastTiming(),
            sessionIdBase = sessionIdBase,
            clock = drivers.fixedClock(),
        )
        return CoreAwaitedInRealTime(core)
    }

    private inner class CoreAwaitedInRealTime(private val core: ControlRuntime) : ControlRuntime by core {
        override suspend fun refreshBondedDevices() = awaitingCore { core.refreshBondedDevices() }

        override suspend fun startScan(): OperationResult = awaitingCore { core.startScan() }

        override suspend fun stopScan() = awaitingCore { core.stopScan() }

        override suspend fun selectCurrentHeadset(deviceId: DeviceId) = awaitingCore { core.selectCurrentHeadset(deviceId) }

        override suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult = awaitingCore { core.connect(deviceId, pinned) }

        override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = awaitingCore { core.connectForAddDevice(deviceId) }

        override suspend fun disconnect(): Boolean = awaitingCore { core.disconnect() }

        override suspend fun release(): Boolean = awaitingCore { core.release() }.also { released -> if (!released) refused.incrementAndGet() }

        override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult = awaitingCore { core.set(command, expectedSessionId) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> awaitingCore(call: suspend () -> T): T {
        val pending = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.UNDISPATCHED) { call() }
        test.eventually("the Rust core to answer") { pending.isCompleted }
        return pending.getCompleted()
    }

    override fun close() = drivers.close()

    private inner class RefusableDrivers : PlatformDrivers by drivers {
        override fun gatt(address: String): GattDriver = SharedGatt(drivers.gatt(address))
    }

    private inner class SharedGatt(private val inner: GattDriver) : GattDriver by inner {
        override suspend fun connect() {
            inner.connect()
            shared.opened(OWNER)
        }

        override suspend fun close() {
            if (closeDelayMillis > 0) delay(closeDelayMillis)
            if (closeFails) throw GattException(GattFailureKind.TRANSIENT, "the platform could not close the link")
            inner.close()
            shared.closed(OWNER)
        }
    }
}

private class CountedTransport(private val delegate: ControlTransportAdapter, private val closed: AtomicInteger) :
    ControlTransport by delegate,
    AutoCloseable {
    override fun close() {
        closed.incrementAndGet()
        delegate.close()
    }
}
