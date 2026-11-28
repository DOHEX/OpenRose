package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import io.github.dohex.openrose.ffi.kotlincore.CoreBackedRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope

internal class SharedDrivers(
    private val drivers: PlatformDrivers,
    private val shared: SharedHeadset,
    private val owner: String,
    private val closeDelayMillis: () -> Long = { 0L },
    private val closeFails: () -> Boolean = { false },
) : PlatformDrivers by drivers {
    override fun gatt(address: String): GattDriver = SharedGatt(drivers.gatt(address))

    private inner class SharedGatt(private val inner: GattDriver) : GattDriver by inner {
        override suspend fun connect() {
            inner.connect()
            shared.opened(owner)
        }

        override suspend fun close() {
            if (closeDelayMillis() > 0) delay(closeDelayMillis())
            if (closeFails()) throw GattException(GattFailureKind.TRANSIENT, "the platform could not close the link")
            inner.close()
            shared.closed(owner)
        }
    }
}

internal class CoreAwaitedInRealTime(
    private val test: TestScope,
    private val core: CoreBackedRuntime,
    private val onRelease: (Boolean) -> Unit = {},
    private val replyWithheld: () -> Boolean = { false },
) : CoreBackedRuntime by core {
    override suspend fun refreshBondedDevices() = awaitingCore { core.refreshBondedDevices() }

    override suspend fun startScan(): OperationResult = awaitingCore { core.startScan() }

    override suspend fun stopScan() = awaitingCore { core.stopScan() }

    override suspend fun selectCurrentHeadset(deviceId: DeviceId) = awaitingCore { core.selectCurrentHeadset(deviceId) }

    override suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult = awaitingCore { core.connect(deviceId, pinned) }

    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = awaitingCore { core.connectForAddDevice(deviceId) }

    override suspend fun disconnect(): Boolean = awaitingCore { core.disconnect() }

    override suspend fun release(): Boolean = awaitingCore { core.release() }.also(onRelease)

    override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult = if (replyWithheld()) core.set(command, expectedSessionId) else awaitingCore { core.set(command, expectedSessionId) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> awaitingCore(call: suspend () -> T): T {
        val pending = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.UNDISPATCHED) { call() }
        test.eventually("the Rust core to answer") { pending.isCompleted }
        return pending.getCompleted()
    }
}
