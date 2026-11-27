package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.transport.GattCharacteristic
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.core.transport.GattEvent
import io.github.dohex.openrose.core.transport.GattService
import io.github.dohex.openrose.core.transport.GattSubscription
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import uniffi.openrose_ffi.modelGattProfile
import uniffi.openrose_ffi_simulator.SimulatedHeadset
import uniffi.openrose_headset.ModelProfile
import java.util.concurrent.CopyOnWriteArrayList

internal class ScriptedGatt(
    private val test: TestScope,
    profile: ModelProfile,
    private val device: SimulatedHeadset,
    private val rewrite: (String) -> String,
) : GattDriver,
    HostLink {
    private val gatt = modelGattProfile(profile)
    private val incoming = Channel<GattEvent>(Channel.UNLIMITED)
    private val writer = GattCharacteristic(gatt.writeUuid, canWrite = true, canWriteWithoutResponse = true)
    private val notifier = GattCharacteristic(gatt.notifyUuid, canNotify = true)
    private val sent = CopyOnWriteArrayList<String>()

    @Volatile private var closedLink = false

    @Volatile override var initializing = true

    override val writes: List<String> get() = sent.toList()
    override val closed: Boolean get() = closedLink
    override val events: Flow<GattEvent> = incoming.receiveAsFlow()

    override suspend fun connect() = Unit

    override suspend fun discoverServices(): List<GattService> = listOf(GattService(gatt.serviceUuid, listOf(writer, notifier)))

    override suspend fun requestMtu(preferred: Int): Int = 185

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) = Unit

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) = Unit

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
        sent.add(value.hex())
        if (!initializing) return
        for (reply in device.receive(value)) incoming.trySend(GattEvent.Notification(notifier, rewrite(reply.hex()).bytes()))
    }

    override suspend fun close() {
        closedLink = true
        incoming.close()
    }

    override fun deviceReply(hex: String) {
        test.quiesce()
        incoming.trySend(GattEvent.Notification(notifier, hex.bytes()))
        test.quiesce()
    }
}

internal class ScriptedDrivers(
    private val drivers: PlatformDrivers,
    private val test: TestScope,
    private val profile: ModelProfile,
    private val device: SimulatedHeadset,
    private val rewrite: (String) -> String,
) : PlatformDrivers by drivers {
    val links = CopyOnWriteArrayList<ScriptedGatt>()

    override fun gatt(address: String): GattDriver = ScriptedGatt(test, profile, device, rewrite).also(links::add)
}
