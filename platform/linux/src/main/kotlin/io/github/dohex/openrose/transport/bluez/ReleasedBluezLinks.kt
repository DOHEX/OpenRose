package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.exceptions.DBusExecutionException

internal class ReleasedBluezLinks {
    private class Release(val owner: String) {
        var confirmed = false
        var resumedLocally = false
    }
    private val lock = Mutex()

    @Volatile
    private var released = emptyMap<String, Release>()

    suspend fun resumeLocal(path: String) {
        lock.withLock {
            released[path]?.resumedLocally = true
            released -= path
        }
    }

    suspend fun release(bus: BluezBus, paths: List<String>, confirm: suspend () -> Unit) {
        val release = Release(checkNotNull(bus.owner) { "BlueZ 服务不可用" })
        var confirmed = false
        try {
            lock.withLock {
                val initial = if (paths.isEmpty()) emptyMap() else bus.objects()
                paths.forEach { path -> initial[path]?.let { bus.disconnectAudio(path, release.owner, it) } }
                check(bus.owner == release.owner) { "让出期间 BlueZ 服务已更换" }
                released += paths.associateWith { release }
                if (paths.isNotEmpty()) {
                    val objects = bus.objects()
                    paths.forEach { releaseReturnedLe(bus, it, objects, release) }
                }
            }
            confirm()
            lock.withLock {
                check(!release.resumedLocally) { "本机已重新请求接入耳机" }
                check(bus.owner == release.owner) { "让出期间 BlueZ 服务已更换" }
                release.confirmed = true
                val remaining = paths.filter { released[it] === release }
                if (remaining.isNotEmpty()) {
                    val objects = bus.objects()
                    remaining.forEach { check(releaseReturnedLe(bus, it, objects, release)) { "本机已重新接入耳机音频" } }
                }
                check(bus.owner == release.owner) { "让出期间 BlueZ 服务已更换" }
            }
            confirmed = true
        } finally {
            if (!confirmed) {
                withContext(NonCancellable) {
                    lock.withLock { released = released.filterValues { it !== release } }
                }
            }
        }
    }

    suspend fun observe(bus: BluezBus) {
        bus.signals { released.isNotEmpty() && it.concernsRelease() }.collect { signal ->
            lock.withLock {
                when (signal) {
                    is BluezSignal.Owner -> released = emptyMap()

                    is BluezSignal.Removed -> {
                        if (DEVICE in signal.interfaces) released -= signal.path
                        if (ADAPTER in signal.interfaces) forgetAdapter(signal.path)
                    }

                    is BluezSignal.Added -> signal.interfaces.forEach { (name, values) -> changed(bus, signal.path, name, values) }

                    is BluezSignal.Changed -> changed(bus, signal.path, signal.interfaceName, signal.values)

                    is BluezSignal.PrepareForSleep -> Unit
                }
            }
        }
    }

    private fun forgetAdapter(path: String) {
        released = released.filterKeys { it.substringBeforeLast('/') != path }
    }

    private suspend fun changed(bus: BluezBus, path: String, name: String, values: Map<String, Any?>) {
        when (name) {
            ADAPTER -> if ("Powered" in values && values["Powered"] != true) forgetAdapter(path)
            CLASSIC_BEARER, LE_BEARER -> if (values["Connected"] == true) reconcileSignal(bus, path)
            MEDIA_TRANSPORT -> released.keys.firstOrNull { mediaBelongsTo(it, path, values) }?.let { reconcileSignal(bus, it) }
        }
    }

    private suspend fun reconcileSignal(bus: BluezBus, path: String) {
        val release = released[path] ?: return
        try {
            releaseReturnedLe(bus, path, bus.objects(), release)
        } catch (_: DBusExecutionException) {
        }
    }

    private suspend fun releaseReturnedLe(bus: BluezBus, path: String, objects: BluezObjects, release: Release): Boolean {
        val interfaces = objects[path]
        val stillReleased = bus.owner == release.owner && objects.onPoweredAdapter(path) && interfaces?.get(DEVICE) != null
        val classic = interfaces?.get(CLASSIC_BEARER)?.get("Connected")
        val hasMedia = objects.hasMediaTransport(path)
        if (!stillReleased) {
            released -= path
        } else if (classic == true || hasMedia) {
            if (release.confirmed) released -= path
        } else if (classic == false && interfaces[LE_BEARER]?.get("Connected") == true) {
            bus.disconnectLe(path, release.owner)
        }
        return !stillReleased || (classic != true && !hasMedia)
    }
}

private val releaseInterfaces = setOf(ADAPTER, DEVICE, CLASSIC_BEARER, LE_BEARER, MEDIA_TRANSPORT)

private fun BluezSignal.concernsRelease(): Boolean = when (this) {
    is BluezSignal.Owner -> true
    is BluezSignal.Changed -> interfaceName in releaseInterfaces && ("Connected" in values || "Powered" in values)
    is BluezSignal.Added -> interfaces.keys.any { it in releaseInterfaces }
    is BluezSignal.Removed -> DEVICE in interfaces || ADAPTER in interfaces
    is BluezSignal.PrepareForSleep -> false
}
