package io.github.dohex.openrose

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.location.LocationManager
import io.github.dohex.openrose.bridge.FrameworkLink
import io.github.dohex.openrose.bridge.ModuleHostBinderLink
import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.LocalOwnerIdentity
import io.github.dohex.openrose.core.SwitchingControlRuntime
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.core.transport.DeviceId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the application's side of control. Activities and interface styles only use [runtime], which
 * routes to exactly one owner: the ordinary session this process creates while ordinary control is the
 * saved choice, or the module host in the system Bluetooth process. Opening or closing the interface
 * never decides whether a control session exists.
 */
class OpenRoseApplication : Application() {
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var runtime: SwitchingControlRuntime
        private set
    lateinit var framework: FrameworkLink
        private set
    private var preferencesLoaded = false

    override fun onCreate() {
        super.onCreate()
        val unlocked = userUnlocked()
        framework = FrameworkLink(applicationContext) { controlScope.launch { refreshPlatform() } }
        runtime = SwitchingControlRuntime(
            initialPlatformState = readPlatformState(permissionRequested()),
            initialSettings = if (unlocked) loadSettings() else UserSettings(),
            store = AndroidOwnerStore({ preferences() }, framework) { runtime.state.value.settings },
            ordinaryFactory = { scope, settings, platform ->
                LocalControlRuntime(
                    initialPlatformState = platform,
                    initialSettings = settings,
                    transport = AndroidControlTransport(applicationContext),
                    scope = scope,
                )
            },
            moduleLink = ModuleHostBinderLink(applicationContext),
            scope = controlScope,
            local = LocalOwnerIdentity(
                packageName, if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName,
                Process.myUid(), Process.myPid(),
            ),
            newId = { UUID.randomUUID().toString() },
            userUnlocked = unlocked,
        )
        preferencesLoaded = unlocked
        framework.start()
        refreshPlatform()
        // The fusion device center reads these from the framework; they follow whichever owner's state is mirrored here.
        controlScope.launch(Dispatchers.IO) {
            runtime.state.map { state ->
                listOf(
                    state.settings.fusionSettingsDestination, state.device?.deviceId, state.device?.classicAddress,
                    state.connectionTarget?.deviceId, state.candidates.map { it.deviceId to it.classicAddress },
                    state.bondedDevices.map { it.deviceId to it.classicAddress },
                )
            }.distinctUntilChanged().collect { framework.publishFusion(runtime.state.value) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) loadUnlockedSettings()
                // Re-read system facts instead of trusting broadcast extras.
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED ||
                    intent.action == LocationManager.MODE_CHANGED_ACTION) refreshPlatform()
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT <= 30) addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        // Bluetooth broadcasts may originate in a privileged app with a non-system UID.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        // Unlock may complete between the initial check and receiver registration.
        loadUnlockedSettings()
    }

    fun startScan() { controlScope.launch { runtime.startScan() } }
    fun stopScan() { controlScope.launch { runtime.stopScan() } }
    fun connect(deviceId: DeviceId) { controlScope.launch { runtime.connect(deviceId) } }
    fun connectFromSystem(deviceId: DeviceId) {
        controlScope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.connectFromSystem(deviceId) }
    }
    fun disconnect() { controlScope.launch { runtime.disconnect() } }
    fun applySetting(setting: DeviceSetting, value: FieldValue) {
        controlScope.launch { runtime.set(setting, value) }
    }

    /** Moves the single control session to [way]; the outcome is visible in the runtime state's owner. */
    fun selectControlWay(way: ControlWay) { controlScope.launch { runtime.selectControlWay(way) } }

    /** Stops module control without enabling ordinary control. */
    fun disableModuleControl() { controlScope.launch { runtime.disableModuleControl() } }

    /** True while the saved choice is ordinary control, i.e. while this process may host a session. */
    fun ordinaryControlSelected(): Boolean = runtime.state.value.owner.selected == ControlWay.ORDINARY

    fun updateSettings(settings: UserSettings) {
        if (!userUnlocked()) return
        // The control way only ever changes through an explicit handoff, never through a preference update.
        runtime.updateSettings(settings)
        val effective = runtime.state.value.settings
        preferences().edit().also { editor ->
            UserSettingsCodec.encode(effective).forEach { (key, value) -> editor.putString(key, value) }
        }.apply()
        if (effective.controlWay != ControlWay.ORDINARY) {
            controlScope.launch(Dispatchers.IO) { framework.publishConfig(runtime.state.value.settings) }
        }
    }

    fun refreshPlatform() = runtime.updatePlatformState(readPlatformState(permissionRequested()).copy(
        module = framework.availability(), bridge = framework.diagnostics(),
    ))

    fun markPermissionRequested() {
        if (!userUnlocked()) return
        preferences().edit().putBoolean("permissionRequested", true).apply()
    }

    private fun permissionRequested() = userUnlocked() && preferences().getBoolean("permissionRequested", false)
    private fun loadUnlockedSettings() {
        if (preferencesLoaded || !userUnlocked()) return
        preferencesLoaded = true
        runtime.onUserUnlocked(loadSettings())
        refreshPlatform()
    }
    private fun userUnlocked() = getSystemService(UserManager::class.java).isUserUnlocked
    private fun loadSettings() = UserSettingsCodec.decode(preferences().all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap())
    private fun preferences() = getSharedPreferences("openrose", MODE_PRIVATE)
}
