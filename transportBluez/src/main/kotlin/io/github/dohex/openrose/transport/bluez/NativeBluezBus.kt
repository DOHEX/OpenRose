package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.bluez.Adapter1
import org.bluez.Device1
import org.bluez.GattCharacteristic1
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.NoReply
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CopyOnWriteArrayList

internal class NativeBluezBus private constructor(private val connection: DBusConnection) : BluezBus {
    private val listeners = CopyOnWriteArrayList<Channel<BluezSignal>>()
    override val events: Flow<BluezSignal> = flow {
        val inbox = Channel<BluezSignal>(Channel.UNLIMITED)
        listeners.add(inbox)
        try {
            for (signal in inbox) emit(signal)
        } finally {
            listeners.remove(inbox)
            inbox.cancel()
        }
    }
    private fun publish(signal: BluezSignal) {
        listeners.forEach { it.trySend(signal) }
    }

    @Volatile private var owner: String? = null
    private val handlers = mutableListOf<AutoCloseable>()

    private fun register() {
        handlers += connection.addSigHandler(DBus.NameOwnerChanged::class.java) { s ->
            if (s.name == "org.bluez") {
                owner = s.newOwner.takeIf { it.isNotEmpty() }
                publish(BluezSignal.Owner(s.newOwner.isNotEmpty()))
            }
        }
        owner = try {
            connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).GetNameOwner("org.bluez")
        } catch (_: Exception) {
            null
        }
        handlers += connection.addSigHandler(Properties.PropertiesChanged::class.java) { s ->
            if (s.source == owner) {
                publish(
                    BluezSignal.Changed(
                        s.path,
                        s.interfaceName,
                        s.propertiesChanged.mapValues { unwrap(it.value) } + s.propertiesRemoved.associateWith { null },
                    ),
                )
            }
        }
        handlers += connection.addSigHandler(ObjectManager.InterfacesAdded::class.java) { s ->
            if (s.source == owner) publish(BluezSignal.Added(s.signalSource.path, s.interfaces.keys))
        }
        handlers += connection.addSigHandler(ObjectManager.InterfacesRemoved::class.java) { s ->
            if (s.source == owner) publish(BluezSignal.Removed(s.signalSource.path, s.interfaces.toSet()))
        }
    }
    override suspend fun objects(): BluezObjects = call {
        val managed = connection.getRemoteObject("org.bluez", "/", ObjectManager::class.java).GetManagedObjects()
        managed.entries.associate { (path, interfaces) ->
            path.path to interfaces.mapValues { (_, properties) -> properties.mapValues { unwrap(it.value) } }
        }
    }
    override suspend fun connect(path: String) = call { connection.getRemoteObject("org.bluez", path, Device1::class.java).Connect() }
    override suspend fun startNotify(path: String) = call {
        connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java).StartNotify()
    }
    override suspend fun stopNotify(path: String) = call {
        connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java).StopNotify()
    }
    override suspend fun write(path: String, value: ByteArray, withResponse: Boolean) = call {
        connection.getRemoteObject("org.bluez", path, GattCharacteristic1::class.java)
            .WriteValue(value, mapOf("type" to Variant(if (withResponse) "request" else "command")))
    }
    override suspend fun startDiscovery(path: String) = call {
        val adapter = connection.getRemoteObject("org.bluez", path, Adapter1::class.java)
        adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le")))
        adapter.StartDiscovery()
    }
    override suspend fun stopDiscovery(path: String) = call {
        connection.getRemoteObject("org.bluez", path, Adapter1::class.java).StopDiscovery()
    }
    override suspend fun close() {
        call {
            handlers.forEach { runCatching { it.close() } }
            connection.close()
        }
        listeners.forEach { it.close() }
    }

    companion object {
        suspend fun open(): NativeBluezBus = call {
            val connection = DBusConnectionBuilder.forSystemBus().withShared(false).build()
            try {
                NativeBluezBus(connection).also { it.register() }
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }
        private suspend fun <T> call(block: () -> T): T = try {
            withTimeout(10_000) { runInterruptible(Dispatchers.IO) { block() } }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw NoReply("BlueZ D-Bus 调用超时")
        }
        private fun unwrap(value: Any?): Any? = when (value) {
            is Variant<*> -> unwrap(value.value)
            is DBusPath -> value.path
            is Map<*, *> -> value.entries.associate { unwrap(it.key) to unwrap(it.value) }
            is List<*> -> value.map(::unwrap)
            else -> value
        }
    }
}
