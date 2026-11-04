package io.github.dohex.openrose.module

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import android.util.Log
import io.github.dohex.openrose.AndroidControlTransport
import io.github.dohex.openrose.bridge.AndroidCallerAuthority
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.bridge.HostEndpoint
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.readPlatformState
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hosts the single control session inside `com.android.bluetooth` with the host's own Context, uid and
 * permissions. It starts from the module's persisted configuration once the user has unlocked the device,
 * and needs no OpenRose Activity, Application, app Service or credential published by the app.
 *
 * The endpoint Binder is handed out by an existing host service for [BridgeWire.ACTION_CONTROL_HOST];
 * finding it authorises nothing, [HostEndpoint] verifies every caller.
 */
internal class ModuleHostRuntime(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {
    private val lifecycle = Any()
    private val hooks = mutableListOf<XposedInterface.HookHandle>()
    @Volatile private var endpoint: HostEndpoint? = null
    private var host: ModuleControlHost? = null
    private var scope: CoroutineScope? = null
    private var context: Context? = null
    private var config: SharedPreferences? = null
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var receiver: BroadcastReceiver? = null
    private var closed = false

    /** Hooks the discovery service so the endpoint can be bound. Safe before the host has started. */
    fun installDiscovery() {
        val type = runCatching { classLoader.loadClass(BridgeWire.HOST_SERVICE_CLASS) }.getOrNull() ?: run {
            module.log(Log.WARN, TAG, "discovery_service_missing ${BridgeWire.HOST_SERVICE_CLASS}")
            return
        }
        val onBind = resolveOnBind(type) ?: run {
            module.log(Log.WARN, TAG, "discovery_onbind_missing ${type.name}")
            return
        }
        try {
            val handle = module.hook(onBind).setId("openrose.host.discovery")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val intent = chain.getArg(0) as? Intent
                    if (intent?.action == BridgeWire.ACTION_CONTROL_HOST) endpoint as IBinder?
                    else chain.proceed()
                }
            synchronized(lifecycle) { hooks += handle }
        } catch (failure: Throwable) {
            module.log(Log.ERROR, TAG, "discovery_hook_failed", failure)
        }
    }

    private fun resolveOnBind(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod("onBind", Intent::class.java) }.getOrNull()?.let { method ->
                if (IBinder::class.java.isAssignableFrom(method.returnType)) return method
            }
            current = current.superclass
        }
        return null
    }

    /** Creates the host owner machinery. Control still waits for first unlock and the saved choice. */
    fun start(hostContext: Context) {
        synchronized(lifecycle) {
            if (closed || host != null) return
            val appContext = hostContext.applicationContext ?: hostContext
            context = appContext
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = hostScope
            val facts = MutableStateFlow(HostFacts())
            val created = ModuleControlHost(
                packageName = appContext.packageName,
                processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else appContext.packageName,
                incarnation = UUID.randomUUID().toString(),
                authority = AndroidCallerAuthority(appContext),
                runtimeFactory = { runtimeScope, settings, platform, sessionIdBase ->
                    LocalControlRuntime(
                        initialPlatformState = platform,
                        initialSettings = settings,
                        transport = AndroidControlTransport(appContext),
                        scope = runtimeScope,
                        sessionIdBase = sessionIdBase,
                    )
                },
                parentScope = hostScope,
                newId = { UUID.randomUUID().toString() },
                uid = Process.myUid(),
                pid = Process.myPid(),
                facts = facts,
            )
            host = created
            endpoint = HostEndpoint(appContext, created, facts, hostScope)
            watchConfiguration(appContext, hostScope, created)
        }
    }

    /**
     * The persisted choice is read fresh every time the host is reconfigured, never cached from an earlier
     * moment: unlock, adapter state or a saved change each re-read it. Until the framework serves the
     * preferences the host stays on its default (no owner), and keeps asking, so the saved module path is
     * restored as soon as it is readable without any help from the OpenRose application.
     */
    private fun watchConfiguration(appContext: Context, hostScope: CoroutineScope, created: ModuleControlHost) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reconfigure(appContext, hostScope, created) }
        configListener = listener
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reconfigure(appContext, hostScope, created)
        }
        receiver = unlockReceiver
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED).apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(unlockReceiver, filter)
        }
        // Unlock may complete between the module loading and the receiver registration.
        reconfigure(appContext, hostScope, created)
    }

    private fun reconfigure(appContext: Context, hostScope: CoroutineScope, created: ModuleControlHost) {
        hostScope.launch {
            created.updatePlatform(appContext.readPlatformState(permissionRequested = true))
            // Read after the last suspension so a later reconfiguration always carries the later facts.
            val unlocked = appContext.getSystemService(UserManager::class.java).isUserUnlocked
            val prefs = openConfig()
            created.configure(HostConfiguration(unlocked, readSettings(prefs)))
            if (prefs == null || !unlocked) {
                delay(CONFIG_RETRY_MILLIS)
                reconfigure(appContext, hostScope, created)
            }
        }
    }

    /** Opens the module's remote preferences once they can be served, and starts listening to them. */
    private fun openConfig(): SharedPreferences? = synchronized(lifecycle) {
        if (closed) return null
        config ?: runCatching { module.getRemotePreferences(BridgeWire.CONFIG_GROUP) }
            .onFailure { module.log(Log.WARN, TAG, "config_unavailable", it) }.getOrNull()
            ?.also { prefs ->
                config = prefs
                configListener?.let { listener ->
                    runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
                        .onFailure { module.log(Log.WARN, TAG, "config_listener_unavailable", it) }
                }
            }
    }

    private fun readSettings(prefs: SharedPreferences?): UserSettings {
        val values = runCatching { prefs?.all }.getOrNull().orEmpty().mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        return UserSettingsCodec.decode(values)
    }

    /**
     * Releases the owner and, only once that release is confirmed, withdraws the endpoint, discovery and
     * scope. When the release cannot be confirmed in time nothing is torn down and false is returned: the
     * host keeps serving and keeps owning the connection, and [close] may be called again to retry. A
     * replacement instance reads the persisted intent and owns with a new generation.
     */
    fun close(): Boolean {
        val (current, hostScope) = synchronized(lifecycle) {
            if (closed) return true
            host to scope
        }
        if (current != null && runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { current.stop() } } != true) return false
        synchronized(lifecycle) {
            closed = true
            hooks.forEach { runCatching { it.unhook() } }
            hooks.clear()
            receiver?.let { r -> runCatching { context?.unregisterReceiver(r) } }
            receiver = null
            configListener?.let { l -> runCatching { config?.unregisterOnSharedPreferenceChangeListener(l) } }
            configListener = null
        }
        endpoint?.shutdown()
        endpoint = null
        hostScope?.cancel()
        return true
    }

    private companion object {
        const val TAG = "OpenRoseModuleHost"
        const val CLOSE_TIMEOUT_MILLIS = 2_000L
        const val CONFIG_RETRY_MILLIS = 5_000L
    }
}
