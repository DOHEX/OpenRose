package io.github.dohex.openrose.module

import android.annotation.SuppressLint
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
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.readHostSettings
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.module.nativecore.NativeCoreTrial
import io.github.dohex.openrose.module.nativecore.androidNativeCoreTrial
import io.github.dohex.openrose.platform.AndroidControlTransport
import io.github.dohex.openrose.platform.readPlatformState
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.error.HookFailedError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.UUID

internal class ModuleHostRuntime(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {
    private val lifecycle = Any()

    @Volatile private var endpoint: HostEndpoint? = null

    @Volatile private var circulation: OwnerCirculation? = null
    private var host: ModuleControlHost? = null
    private var scope: CoroutineScope? = null
    private var config: SharedPreferences? = null
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @SuppressLint("PrivateApi")
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
            module.hook(onBind).setId("openrose.host.discovery")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val intent = chain.getArg(0) as? Intent
                    if (intent?.action == BridgeWire.ACTION_CONTROL_HOST) {
                        endpoint ?: chain.proceed()
                    } else {
                        chain.proceed()
                    }
                }
        } catch (failure: IllegalArgumentException) {
            module.log(Log.ERROR, TAG, "discovery_hook_failed", failure)
        } catch (failure: HookFailedError) {
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

    fun start(hostContext: Context) {
        synchronized(lifecycle) {
            if (host != null) return
            val appContext = hostContext.applicationContext ?: hostContext
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = hostScope
            val trial = androidNativeCoreTrial(module, appContext)
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
                onOwnerStarted = { generation, runtimeScope, control ->
                    val service = startPhoneCirculation(appContext, runtimeScope, control)
                    circulation = OwnerCirculation(generation, runtimeScope, service::call)
                },
                parentScope = hostScope,
                newId = { UUID.randomUUID().toString() },
                uid = Process.myUid(),
                pid = Process.myPid(),
                facts = facts,
            )
            host = created
            val served = HostEndpoint(appContext, created, facts, hostScope) { circulation }
            endpoint = served
            watchConfiguration(appContext, hostScope, created, trial)
        }
    }

    private fun watchConfiguration(
        appContext: Context,
        hostScope: CoroutineScope,
        created: ModuleControlHost,
        trial: NativeCoreTrial,
    ) {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changes.trySend(Unit) }
        configListener = listener
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changes.trySend(Unit)
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED).apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(unlockReceiver, filter)
        }
        hostScope.launch {
            val user = appContext.getSystemService(UserManager::class.java)
            while (isActive) {
                created.updatePlatform(appContext.readPlatformState(permissionRequested = true))
                val configured = reconcileConfiguration(user, created, trial, changes)
                if (configured && user.isUserUnlocked) {
                    changes.receive()
                } else {
                    delay(CONFIG_RETRY_MILLIS)
                }
            }
        }
    }

    private suspend fun reconcileConfiguration(
        user: UserManager,
        created: ModuleControlHost,
        trial: NativeCoreTrial,
        changes: Channel<Unit>,
    ): Boolean {
        val initial = if (user.isUserUnlocked) readConfiguration() else HostConfiguration()
        if (initial == null) {
            changes.trySend(Unit)
        } else if (initial.userUnlocked && initial.settings.integration.controlWay == ControlWay.MODULE) {
            withContext(Dispatchers.Default) {
                trial.apply(initial.settings.integration.nativeCoreTrial, ControlWay.MODULE)
            }
        }
        var firstRead = true
        val configured = created.configure {
            if (firstRead) {
                firstRead = false
                initial
            } else if (user.isUserUnlocked) {
                readConfiguration().also { if (it == null) changes.trySend(Unit) }
            } else {
                HostConfiguration()
            }
        }
        if (initial != null && initial.userUnlocked) {
            val effectiveWay = if (created.status.value is HostStatus.Active) {
                ControlWay.MODULE
            } else {
                initial.settings.integration.controlWay
            }
            withContext(Dispatchers.Default) {
                trial.apply(initial.settings.integration.nativeCoreTrial, effectiveWay)
            }
        }
        return configured
    }

    private fun readConfiguration(): HostConfiguration? = synchronized(lifecycle) {
        val listener = configListener ?: return null
        val prefs = config ?: runCatching {
            module.getRemotePreferences(BridgeWire.CONFIG_GROUP).also {
                it.registerOnSharedPreferenceChangeListener(listener)
                config = it
            }
        }.getOrElse { failure ->
            module.log(Log.WARN, TAG, "config_unavailable", failure)
            return null
        }
        val settings = readHostSettings(prefs)
        if (settings == null) {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            config = null
            return null
        }
        HostConfiguration(userUnlocked = true, settings = settings)
    }

    private companion object {
        const val TAG = "OpenRoseModuleHost"
        const val CONFIG_RETRY_MILLIS = 5_000L
    }
}
