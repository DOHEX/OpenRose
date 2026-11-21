package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
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
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.NoReply
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CopyOnWriteArrayList

@DBusInterfaceName("org.freedesktop.login1.Manager")
internal interface LoginManager : DBusInterface {
    class PrepareForSleep(path: String, val sleeping: Boolean) : DBusSignal(path, sleeping)
}

internal class NativeBluezBus private constructor(private val connection: DBusConnection) :
    BluezBus,
    BluezAudioBus by NativeBluezAudioBus(connection) {
    private class Listener(val accepts: (BluezSignal) -> Boolean, val inbox: Channel<BluezSignal> = Channel(Channel.BUFFERED))
    private val listeners = CopyOnWriteArrayList<Listener>()
    override fun signals(accepts: (BluezSignal) -> Boolean): Flow<BluezSignal> = flow {
        val listener = Listener(accepts)
        listeners.add(listener)
        try {
            for (signal in listener.inbox) emit(signal)
        } finally {
            listeners.remove(listener)
            listener.inbox.cancel()
        }
    }
    private fun publish(signal: BluezSignal) {
        listeners.forEach { if (it.accepts(signal)) it.inbox.trySendBlocking(signal) }
    }

    @Volatile override var owner: String? = null
        private set

    @Volatile private var loginOwner: String? = null
    private val handlers = mutableListOf<AutoCloseable>()

    private fun register() {
        handlers += connection.addSigHandler(DBus.NameOwnerChanged::class.java) { s ->
            if (s.name == "org.freedesktop.login1") loginOwner = s.newOwner.takeIf { it.isNotEmpty() }
            if (s.name == "org.bluez") {
                owner = s.newOwner.takeIf { it.isNotEmpty() }
                publish(BluezSignal.Owner(s.newOwner.isNotEmpty()))
            }
        }
        fun busOwner(name: String): String? = try {
            connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).GetNameOwner(name)
        } catch (_: Exception) {
            null
        }
        owner = busOwner("org.bluez")
        loginOwner = busOwner("org.freedesktop.login1")
        handlers += connection.addSigHandler(LoginManager.PrepareForSleep::class.java) { s ->
            if (s.source == loginOwner && s.path == "/org/freedesktop/login1") publish(BluezSignal.PrepareForSleep(s.sleeping))
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
            if (s.source == owner) {
                val properties = s.interfaces.mapValues { (_, fields) -> fields.mapValues { unwrap(it.value) } }
                publish(BluezSignal.Added(s.signalSource.path, properties))
            }
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
    override suspend fun connectGatt(path: String, useLeBearer: Boolean) = call {
        if (useLeBearer) {
            connection.getRemoteObject("org.bluez", path, LeBearer::class.java).connect()
        } else {
            connection.getRemoteObject("org.bluez", path, Device1::class.java).Connect()
        }
    }
    override suspend fun startNotify(owner: String, path: String) = call {
        connection.getRemoteObject(owner, path, GattCharacteristic1::class.java).StartNotify()
    }
    override suspend fun stopNotify(owner: String, path: String) = call {
        connection.getRemoteObject(owner, path, GattCharacteristic1::class.java).StopNotify()
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
        listeners.forEach { it.inbox.close() }
        call {
            handlers.forEach { runCatching { it.close() } }
            connection.close()
        }
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
        internal suspend fun <T> call(block: () -> T): T = try {
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
