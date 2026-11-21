package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.errors.AccessDenied
import org.freedesktop.dbus.errors.NoReply
import org.freedesktop.dbus.errors.ServiceUnknown
import java.util.IdentityHashMap

internal class BluezGattDriver(private val bus: BluezBus, private val devicePath: String) : GattDriver {
    private val owner = bus.owner ?: throw GattException(GattFailureKind.DISCONNECTED, "BlueZ 服务不可用")
    private val lock = Any()
    private val characteristics = IdentityHashMap<GattCharacteristic, String>()
    private val routed = mutableSetOf<String>()
    private val subscribed = mutableSetOf<String>()
    private var discovered = false
    private var closed = false
    override val events: Flow<GattEvent> = bus.signals { it.affectsLink(devicePath) }.transform { signal ->
        when (signal) {
            is BluezSignal.PrepareForSleep -> if (signal.sleeping) emit(disconnected("电脑进入休眠"))

            is BluezSignal.Owner -> if (!signal.present || bus.owner != owner) emit(disconnected("BlueZ 服务已停止"))

            is BluezSignal.Changed -> {
                if ((signal.path == devicePath && signal.interfaceName == DEVICE && signal.values["Connected"] == false) ||
                    (devicePath.startsWith("${signal.path}/") && signal.interfaceName == ADAPTER && signal.values["Powered"] == false)
                ) {
                    emit(disconnected("蓝牙设备已断开"))
                }
                if (signal.interfaceName == CHARACTERISTIC) {
                    val instance = synchronized(lock) {
                        if (signal.path in routed) characteristics.entries.firstOrNull { it.value == signal.path }?.key else null
                    }
                    val value = signal.values["Value"].asBytes()
                    if (value != null && instance != null) emit(GattEvent.Notification(instance, value))
                }
                if (synchronized(lock) { discovered } && signal.path == devicePath && signal.interfaceName == DEVICE &&
                    signal.values.containsKey("ServicesResolved") && signal.values["ServicesResolved"] != true
                ) {
                    emit(GattEvent.ServicesChanged)
                }
            }

            is BluezSignal.Removed -> {
                if ((signal.path == devicePath && DEVICE in signal.interfaces) ||
                    (devicePath.startsWith("${signal.path}/") && ADAPTER in signal.interfaces)
                ) {
                    emit(disconnected("蓝牙设备已移除"))
                } else if (synchronized(lock) { discovered } && signal.path.startsWith("$devicePath/") &&
                    (SERVICE in signal.interfaces || CHARACTERISTIC in signal.interfaces)
                ) {
                    emit(GattEvent.ServicesChanged)
                }
            }

            is BluezSignal.Added -> if (synchronized(lock) { discovered } && signal.path.startsWith("$devicePath/") &&
                (SERVICE in signal.interfaces || CHARACTERISTIC in signal.interfaces)
            ) {
                emit(GattEvent.ServicesChanged)
            }
        }
    }
    override suspend fun connect() = native {
        checkOpen()
        bus.requestGattConnection(devicePath)
    }
    override suspend fun discoverServices(): List<GattService> = native {
        checkOpen()
        suspend fun waitForServices(timeout: Long): BluezObjects? = withTimeoutOrNull(timeout) {
            while (true) {
                val objects = bus.objects()
                if (objects[devicePath]?.get(DEVICE)?.get("ServicesResolved") == true &&
                    objects.any { it.key.startsWith("$devicePath/") && SERVICE in it.value }
                ) {
                    return@withTimeoutOrNull objects
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        var objects = waitForServices(2_000)
        if (objects == null) {
            // If the first Connect chose the classic bearer, BlueZ selects the disconnected bearer on a second call.
            bus.requestGattConnection(devicePath)
            objects = waitForServices(8_000) ?: throw GattException(GattFailureKind.TRANSIENT, "等待 BlueZ GATT 服务超时")
        }
        val found = IdentityHashMap<GattCharacteristic, String>()
        val services = objects.filter { (path, interfaces) ->
            path.startsWith("$devicePath/") && SERVICE in interfaces
        }.mapNotNull { (path, interfaces) ->
            val uuid = (interfaces[SERVICE]?.get("UUID") as? String)?.lowercase() ?: return@mapNotNull null
            val chars = objects.filter { (charPath, values) ->
                charPath.startsWith("$path/") && CHARACTERISTIC in values
            }.mapNotNull char@{ (charPath, values) ->
                val properties = values[CHARACTERISTIC] ?: return@char null
                val charUuid = (properties["UUID"] as? String)?.lowercase() ?: return@char null
                val flags = (properties["Flags"] as? Collection<*>)?.toSet().orEmpty()
                GattCharacteristic(
                    charUuid,
                    "write" in flags,
                    "write-without-response" in flags,
                    "notify" in flags,
                    "indicate" in flags,
                ).also { found[it] = charPath }
            }
            GattService(uuid, chars)
        }
        synchronized(lock) {
            characteristics.clear()
            characteristics.putAll(found)
            discovered = true
        }
        services
    }
    override suspend fun requestMtu(preferred: Int): Int? = native {
        checkOpen()
        val objects = bus.objects()
        synchronized(lock) { characteristics.values.toList() }
            .mapNotNull { (objects[it]?.get(CHARACTERISTIC)?.get("MTU") as? Number)?.toInt() }
            .minOrNull()
    }
    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) {
        checkOpen()
        val path = path(characteristic)
        synchronized(lock) { if (enabled) routed.add(path) else routed.remove(path) }
    }
    override suspend fun configureSubscription(
        characteristic: GattCharacteristic,
        subscription: GattSubscription,
    ) = native(recoverNotConnected = true) {
        checkOpen()
        val path = path(characteristic)
        if (subscription == GattSubscription.DISABLED) {
            if (synchronized(lock) { path in subscribed }) {
                releaseSubscription(path)
            }
        } else if (synchronized(lock) { path !in subscribed }) {
            bus.startNotify(owner, path)
            synchronized(lock) { subscribed.add(path) }
        }
    }
    override suspend fun write(
        characteristic: GattCharacteristic,
        value: ByteArray,
        withResponse: Boolean,
    ) = native(recoverNotConnected = true) {
        checkOpen()
        bus.write(path(characteristic), value, withResponse)
    }
    override suspend fun close() {
        if (synchronized(lock) { closed }) return
        for (path in synchronized(lock) { subscribed.toList() }) {
            native { releaseSubscription(path) }
        }
        synchronized(lock) {
            routed.clear()
            closed = true
        }
        // Device1.Disconnect tears down all profiles, including audio. StopNotify releases our lease.
    }
    private suspend fun releaseSubscription(path: String) {
        // A BlueZ restart destroys the old daemon's subscriptions, even if link observation has already stopped.
        if (bus.owner == owner) bus.stopNotify(owner, path)
        synchronized(lock) { subscribed.remove(path) }
    }
    private fun checkOpen() {
        if (synchronized(lock) { closed }) throw GattException(GattFailureKind.DISCONNECTED, "GATT 已关闭")
    }
    private fun path(characteristic: GattCharacteristic): String = synchronized(lock) { characteristics[characteristic] }
        ?: throw GattException(GattFailureKind.INVALID_VALUE, "未知 GATT 特征实例")
    private suspend fun <T> native(recoverNotConnected: Boolean = false, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: GattException) {
        throw error
    } catch (error: Exception) {
        val kind = when (error) {
            is org.bluez.Error.NotAuthorized, is org.bluez.Error.NotPermitted, is AccessDenied -> GattFailureKind.PERMISSION

            is org.bluez.Error.NotSupported -> GattFailureKind.UNSUPPORTED

            is org.bluez.Error.InvalidArguments,
            is org.bluez.Error.InvalidValueLength,
            is org.bluez.Error.InvalidOffset,
            -> GattFailureKind.INVALID_VALUE

            is org.bluez.Error.NotConnected -> if (recoverNotConnected) GattFailureKind.TRANSIENT else GattFailureKind.DISCONNECTED

            is ServiceUnknown -> GattFailureKind.DISCONNECTED

            is org.bluez.Error.InProgress, is org.bluez.Error.NotReady, is NoReply -> GattFailureKind.TRANSIENT

            else -> GattFailureKind.UNKNOWN
        }
        throw GattException(kind, error.message ?: error.javaClass.simpleName, error)
    }
}

private suspend fun BluezBus.requestGattConnection(devicePath: String) {
    try {
        connect(devicePath)
    } catch (_: org.bluez.Error.AlreadyConnected) {
        // Continue into bounded service discovery on the existing connection.
    } catch (error: org.bluez.Error.InProgress) {
        // BlueZ can still be completing an audio profile when it publishes the media transport.
        if (objects()[devicePath]?.get(DEVICE)?.get("Connected") != true) throw error
    }
}

private fun disconnected(message: String) = GattEvent.Disconnected(GattException(GattFailureKind.DISCONNECTED, message))

internal fun Any?.asBytes(): ByteArray? = when (this) {
    is ByteArray -> copyOf()
    is List<*> -> mapNotNull { (it as? Number)?.toByte() }.toByteArray()
    else -> null
}

private fun BluezSignal.affectsLink(devicePath: String): Boolean = when (this) {
    is BluezSignal.Changed -> when (interfaceName) {
        ADAPTER -> devicePath.startsWith("$path/")
        DEVICE -> path == devicePath && ("Connected" in values || "ServicesResolved" in values)
        CHARACTERISTIC -> path.startsWith("$devicePath/") && "Value" in values
        else -> false
    }

    else -> true
}
