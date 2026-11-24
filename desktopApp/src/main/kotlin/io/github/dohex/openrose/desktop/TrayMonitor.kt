package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.ui.desktop.TrayAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import java.util.concurrent.atomic.AtomicBoolean

internal interface TrayMonitor : AutoCloseable {
    val nativeReady: Boolean
    val availability: StateFlow<TrayAvailability>

    suspend fun refresh(): TrayAvailability

    fun trayStarted()
}

internal fun trayMonitor(hostIsLinux: Boolean): TrayMonitor = if (hostIsLinux) StatusNotifierTrayMonitor() else ShellTrayMonitor

private object ShellTrayMonitor : TrayMonitor {
    override val nativeReady = true
    override val availability: StateFlow<TrayAvailability> = MutableStateFlow(TrayAvailability.Available)
    override suspend fun refresh(): TrayAvailability = TrayAvailability.Available
    override fun trayStarted() = Unit
    override fun close() = Unit
}

private class StatusNotifierTrayMonitor : TrayMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableAvailability = MutableStateFlow<TrayAvailability>(TrayAvailability.Checking)
    private val started = AtomicBoolean(false)
    private val itemName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
    private val nativeFailure: String? = runCatching {
        Class.forName(NATIVE_BRIDGE, true, StatusNotifierTrayMonitor::class.java.classLoader)
    }.exceptionOrNull()?.let { error ->
        generateSequence(error) { it.cause }.last().message ?: error.toString()
    }
    private val connection: Result<DBusConnection> =
        if (nativeFailure != null) {
            Result.failure(IllegalStateException(nativeFailure))
        } else {
            runCatching { DBusConnectionBuilder.forSessionBus().build() }
        }

    private val registering = AtomicBoolean(false)

    @Volatile private var recheck: Job? = null

    override val nativeReady = nativeFailure == null
    override val availability: StateFlow<TrayAvailability> = mutableAvailability.asStateFlow()

    init {
        when {
            nativeFailure != null -> mutableAvailability.value = TrayAvailability.Unavailable("托盘组件无法加载：$nativeFailure")

            connection.isFailure -> mutableAvailability.value = TrayAvailability.Unavailable(
                "无法连接会话总线：${connection.exceptionOrNull()?.message ?: "未知原因"}",
            )

            else -> {
                connection.getOrThrow().addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                    if (signal.name == WATCHER || signal.name == itemName) scheduleRecheck()
                }
                scheduleRecheck(delayMs = 0)
            }
        }
    }

    override fun trayStarted() {
        if (!started.compareAndSet(false, true) || connection.isFailure) return
        registering.set(true)
        scope.launch {
            var result: TrayAvailability = TrayAvailability.Checking
            for (attempt in 1..REGISTRATION_ATTEMPTS) {
                result = evaluate()
                if (result == TrayAvailability.Available) break
                delay(REGISTRATION_POLL_MS)
            }
            registering.set(false)
            mutableAvailability.value = result
        }
    }

    override suspend fun refresh(): TrayAvailability = withContext(Dispatchers.IO) {
        evaluate().also { mutableAvailability.value = it }
    }

    private fun evaluate(): TrayAvailability {
        val bus = connection.getOrNull() ?: return mutableAvailability.value
        return runCatching { read(bus) }.getOrElse {
            TrayAvailability.Unavailable("无法读取托盘宿主：${it.message ?: it.toString()}")
        }
    }

    private fun read(bus: DBusConnection): TrayAvailability {
        val dbus = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        if (!dbus.NameHasOwner(WATCHER)) {
            return TrayAvailability.Unavailable("当前桌面没有托盘宿主（GNOME 需启用 AppIndicator 扩展）")
        }
        val watcher = bus.getRemoteObject(WATCHER, WATCHER_PATH, Properties::class.java)
        if (watcher.Get<Boolean>(WATCHER, "IsStatusNotifierHostRegistered") != true) {
            return TrayAvailability.Unavailable("托盘宿主尚未运行")
        }
        if (!started.get()) return TrayAvailability.Checking
        val owner = if (dbus.NameHasOwner(itemName)) dbus.GetNameOwner(itemName) else null
        val registered = watcher.Get<Any>(WATCHER, "RegisteredStatusNotifierItems").entries().any { entry ->
            val service = entry.substringBefore('/')
            service == itemName || (owner != null && service == owner)
        }
        return if (registered) {
            TrayAvailability.Available
        } else {
            TrayAvailability.Unavailable("托盘宿主没有显示 OpenRose 图标")
        }
    }

    private fun scheduleRecheck(delayMs: Long = REREGISTER_DELAY_MS) {
        recheck?.cancel()
        recheck = scope.launch {
            delay(delayMs)
            val next = evaluate()
            if (!registering.get()) mutableAvailability.value = next
        }
    }

    override fun close() {
        scope.cancel()
        connection.getOrNull()?.let { runCatching { it.close() } }
    }

    private companion object {
        const val NATIVE_BRIDGE = "dev.nucleusframework.composenativetray.lib.linux.LinuxNativeBridge"
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
        const val REGISTRATION_ATTEMPTS = 20
        const val REGISTRATION_POLL_MS = 500L
        const val REREGISTER_DELAY_MS = 1_000L
    }
}

private fun Any?.entries(): List<String> = when (this) {
    is Array<*> -> mapNotNull { it as? String }
    is Iterable<*> -> mapNotNull { it as? String }
    else -> emptyList()
}
