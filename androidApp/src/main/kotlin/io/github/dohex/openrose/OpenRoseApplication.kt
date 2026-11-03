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
import io.github.dohex.openrose.core.BackgroundControlBlock
import io.github.dohex.openrose.core.BackgroundControlStatus
import io.github.dohex.openrose.core.BackgroundServiceFact
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
import kotlinx.coroutines.launch

/** The names of the application's own preference file and its flags, shared by every reader and writer. */
internal object AppPreferences {
    const val FILE = "openrose"
    const val PERMISSION_REQUESTED = "permissionRequested"
    const val NOTIFICATION_REQUESTED = "notificationRequested"
}

/**
 * Hosts the application's side of control. Activities, interface styles and [ControlService] only use or
 * observe [runtime], which routes to exactly one owner: the ordinary session this process creates while
 * ordinary control is the saved choice, or the module host in the system Bluetooth process. The ordinary
 * session exists only while it is held: by the started interface ([VisibleInterfaceHold]) or by
 * [ControlService]. A task being removed never decides whether a control session exists.
 */
class OpenRoseApplication : Application() {
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var runtime: SwitchingControlRuntime
        private set
    lateinit var framework: FrameworkLink
        private set
    private lateinit var ownerStore: AndroidOwnerStore
    private val companionWake by lazy { CompanionWake(this) }
    private var preferencesLoaded = false
    private var backgroundService: BackgroundServiceFact = BackgroundServiceFact.NotRunning

    override fun onCreate() {
        super.onCreate()
        val unlocked = userUnlocked()
        framework = FrameworkLink { controlScope.launch { refreshPlatform() } }
        ownerStore = AndroidOwnerStore({ preferences() }, framework) { runtime.state.value.settings }
        runtime = SwitchingControlRuntime(
            initialPlatformState = readPlatformState(permissionRequested(), wake = companionWake.facts(), userUnlocked = unlocked),
            initialSettings = if (unlocked) loadSettings() else UserSettings(),
            store = ownerStore,
            ordinaryFactory = { scope, settings, platform, sessionIdBase ->
                LocalControlRuntime(
                    initialPlatformState = platform,
                    initialSettings = settings,
                    transport = AndroidControlTransport(applicationContext),
                    scope = scope,
                    sessionIdBase = sessionIdBase,
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
        registerActivityLifecycleCallbacks(VisibleInterfaceHold(runtime, controlScope))
        framework.start()
        refreshPlatform()
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
        controlScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The request arrives while the launching activity is not started yet; the claim bridges that gap
            // and ends with the call, by which time the started interface holds the session itself.
            val hold = runtime.holdOrdinarySession()
            try {
                runtime.connectFromSystem(deviceId)
            } finally {
                hold.release()
            }
        }
    }
    fun disconnect() { controlScope.launch { runtime.disconnect() } }
    fun applySetting(setting: DeviceSetting, value: FieldValue) {
        controlScope.launch { runtime.set(setting, value) }
    }

    /**
     * Moves the single control session to [way]; the outcome is visible in the runtime state's owner.
     * Whatever the outcome, wake-up observation and the background service follow the saved choice after.
     */
    fun selectControlWay(way: ControlWay) {
        controlScope.launch {
            runtime.selectControlWay(way)
            followSavedControlWay()
        }
    }

    /** Stops module control without enabling ordinary control. */
    fun disableModuleControl() {
        controlScope.launch {
            runtime.disableModuleControl()
            followSavedControlWay()
        }
    }

    /**
     * After a handoff: presence observation is withdrawn when ordinary control is no longer the saved
     * choice, and the stopped service is started again (a user-initiated action) when it is and background
     * control is enabled.
     */
    private fun followSavedControlWay() {
        syncWakeObservation()
        if (ordinaryControlSelected() && runtime.state.value.settings.backgroundControlEnabled) {
            startBackgroundControlFromUserEntry()
        }
    }

    /**
     * True while the saved choice is ordinary control, i.e. while this process may host a session. Services
     * and wake-ups for ordinary background control check this; they never create a session themselves.
     */
    fun ordinaryControlSelected(): Boolean = runtime.state.value.owner.selected == ControlWay.ORDINARY

    fun updateSettings(settings: UserSettings) {
        if (!userUnlocked()) return
        val backgroundIntentChanged = runtime.state.value.settings.backgroundControlEnabled != settings.backgroundControlEnabled
        // The control way only ever changes through an explicit handoff, never through a preference update.
        runtime.updateSettings(settings)
        val effective = runtime.state.value.settings
        val editor = preferences().edit()
        UserSettingsCodec.encode(effective).forEach { (key, value) -> editor.putString(key, value) }
        // A stop choice must not be lost to a process kill right after the user disables background control.
        if (backgroundIntentChanged) editor.commit() else editor.apply()
        if (effective.controlWay != ControlWay.ORDINARY) {
            // The single host-config writer derives the control way from the persisted record at write time.
            controlScope.launch(Dispatchers.IO) { ownerStore.publishConfig() }
        }
        syncWakeObservation()
        if (effective.backgroundControlEnabled) startBackgroundControlFromUserEntry()
    }

    fun setBackgroundControlEnabled(enabled: Boolean) {
        updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = enabled))
    }

    /**
     * Starts the foreground service for the saved background-control intent from an entry the user is
     * looking at (toggle, opening the app, a permission result). It is never a retry loop.
     */
    fun startBackgroundControlFromUserEntry() = startBackgroundService()

    /**
     * Entry of the system-delivered wake-ups (first unlock of a boot, an audio connection while the
     * process was gone, device presence). It only starts the service when ordinary control is the saved
     * choice and the restored saved intent, first unlock and Bluetooth permission allow it, so a disabled,
     * locked or module-controlled state is never revived. It creates no session itself: the service it
     * starts holds the ordinary session, and the session ends when the service does.
     */
    fun wakeOrdinaryControl() {
        loadUnlockedSettings()
        if (!ordinaryControlSelected()) return
        syncWakeObservation()
        startBackgroundService()
    }

    private fun startBackgroundService() {
        val state = runtime.state.value
        // mayStartBackgroundService is false unless ordinary control is the saved choice.
        if (!state.mayStartBackgroundService || state.backgroundControl is BackgroundControlStatus.Running) return
        updateBackgroundService(BackgroundServiceFact.NotRunning)
        try {
            startForegroundService(Intent(this, ControlService::class.java))
        } catch (denied: SecurityException) {
            backgroundServiceRejected(BackgroundControlBlock.SERVICE_START_REFUSED)
        } catch (restricted: IllegalStateException) {
            backgroundServiceRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)
        }
    }

    /**
     * Presence observation follows the saved intent: observed only while ordinary control is selected,
     * background control is enabled and the user has unlocked; withdrawn otherwise.
     */
    fun syncWakeObservation() {
        if (!preferencesLoaded) return
        val state = runtime.state.value
        companionWake.syncObservation(state.settings.backgroundControlEnabled && state.owner.selected == ControlWay.ORDINARY)
        refreshPlatform()
    }

    fun requestCompanionAssociation(onChooser: (android.content.IntentSender) -> Unit, onFailure: () -> Unit) =
        companionWake.requestAssociation(onChooser, onFailure)

    fun backgroundServiceRunning() = updateBackgroundService(BackgroundServiceFact.Running)
    fun backgroundServiceRejected(reason: BackgroundControlBlock) =
        updateBackgroundService(BackgroundServiceFact.StartRejected(reason))
    /** A rejected start keeps its reason; only a service that had been running becomes "not running". */
    fun backgroundServiceStopped() {
        if (backgroundService == BackgroundServiceFact.Running) updateBackgroundService(BackgroundServiceFact.NotRunning)
    }

    private fun updateBackgroundService(fact: BackgroundServiceFact) {
        backgroundService = fact
        refreshPlatform()
    }

    fun refreshPlatform() = runtime.updatePlatformState(
        readPlatformState(permissionRequested(), backgroundService, companionWake.facts(), preferencesLoaded).copy(
            module = framework.availability(), bridge = framework.diagnostics(),
        ),
    )

    fun markPermissionRequested() {
        if (!userUnlocked()) return
        preferences().edit().putBoolean(AppPreferences.PERMISSION_REQUESTED, true).apply()
    }

    private fun permissionRequested() = userUnlocked() && preferences().getBoolean(AppPreferences.PERMISSION_REQUESTED, false)
    private fun loadUnlockedSettings() {
        if (preferencesLoaded || !userUnlocked()) return
        preferencesLoaded = true
        runtime.onUserUnlocked(loadSettings())
        refreshPlatform()
        syncWakeObservation()
    }
    private fun userUnlocked() = getSystemService(UserManager::class.java).isUserUnlocked
    private fun loadSettings() = UserSettingsCodec.decode(preferences().all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap())
    private fun preferences() = getSharedPreferences(AppPreferences.FILE, MODE_PRIVATE)
}
