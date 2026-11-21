package io.github.dohex.openrose

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.LocalOwnerIdentity
import io.github.dohex.openrose.androidintegration.SwitchingControlRuntime
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.framework.FrameworkLink
import io.github.dohex.openrose.framework.ModuleHostBinderLink
import io.github.dohex.openrose.platform.AndroidControlTransport
import io.github.dohex.openrose.platform.readPlatformState
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.model.OpenRoseSettingsStore
import io.github.dohex.openrose.ui.model.RuntimeOpenRoseModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Hosts the application's side of control. Activities, interface styles and [ControlService] only use or
 * observe [runtime], which routes to exactly one owner: the ordinary session this process creates while
 * ordinary control is the saved choice, or the module host in the system Bluetooth process. The ordinary
 * session exists only while it is held: by the started interface ([VisibleInterfaceHold]) or by
 * [ControlService]. A task being removed never decides whether a control session exists.
 */
class OpenRoseApplication : Application() {
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val moduleSettingsChanges = Channel<Unit>(Channel.CONFLATED)
    internal val permissions = AppPreferences(this)
    private val settingsStore = AndroidSettingsStore(permissions::open)
    private val mutableUiSettings = MutableStateFlow(UiSettings())
    val uiSettings = mutableUiSettings.asStateFlow()
    lateinit var runtime: SwitchingControlRuntime
        private set
    lateinit var model: RuntimeOpenRoseModel
        private set
    lateinit var framework: FrameworkLink
        private set
    private lateinit var ownerStore: AndroidOwnerStore
    private val moduleLink by lazy { ModuleHostBinderLink(applicationContext) }
    val circulation by lazy { CirculationClient(moduleLink::circulation) }
    private val companionWake by lazy { CompanionWake(this) }
    private var systemConnectionRequest: Job? = null
    private var preferencesLoaded = false
    internal lateinit var background: OrdinaryBackgroundControl
        private set

    override fun onCreate() {
        super.onCreate()
        val unlocked = permissions.unlocked
        val saved = if (unlocked) settingsStore.load() else AppSettings()
        mutableUiSettings.value = saved.ui
        framework = FrameworkLink {
            controlScope.launch {
                refreshPlatform()
                moduleSettingsChanges.trySend(Unit)
            }
        }
        ownerStore = AndroidOwnerStore(permissions::open, framework)
        runtime = SwitchingControlRuntime(
            initialPlatformState = readPlatformState(permissions.bluetoothRequested, userUnlocked = unlocked),
            initialIntegrationPlatform = readIntegrationPlatform(wake = companionWake.facts()),
            initialSettings = saved.host.control,
            initialIntegrationSettings = saved.host.integration,
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
            moduleLink = moduleLink,
            scope = controlScope,
            local = LocalOwnerIdentity(
                packageName,
                if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName,
                Process.myUid(),
                Process.myPid(),
            ),
            newId = { UUID.randomUUID().toString() },
            userUnlocked = unlocked,
        )
        background = OrdinaryBackgroundControl(this, runtime, companionWake, ::refreshPlatform)
        model = createModel()
        preferencesLoaded = unlocked
        registerActivityLifecycleCallbacks(VisibleInterfaceHold(runtime, controlScope))
        controlScope.launch {
            moduleSettingsChanges.receiveAsFlow().collectLatest {
                while (preferencesLoaded && !background.ordinarySelected && framework.canPublish()) {
                    if (ownerStore.publishSelection()) break
                    delay(3_000)
                }
            }
        }
        framework.start()
        refreshPlatform()
        registerPlatformReceiver()
        // Unlock may complete between the initial check and receiver registration.
        if (unlocked) background.syncObservation() else loadUnlockedSettings()
    }

    private fun createModel() = RuntimeOpenRoseModel(
        runtime,
        object : OpenRoseSettingsStore {
            override val uiSettings = this@OpenRoseApplication.uiSettings

            override fun updateSettings(settings: UserSettings) {
                if (!permissions.unlocked) return
                runtime.updateSettings(settings)
                settingsStore.saveHost(hostSettings())
                moduleSettingsChanges.trySend(Unit)
            }

            override fun updateUiSettings(settings: UiSettings) {
                if (!permissions.unlocked) return
                mutableUiSettings.value = settings
                settingsStore.saveUi(settings)
            }
        },
        controlScope,
    )

    private fun registerPlatformReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) loadUnlockedSettings()
                // Re-read system facts instead of trusting broadcast extras.
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshPlatform()
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        // Bluetooth broadcasts may originate in a privileged app with a non-system UID.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    fun connectFromSystem(deviceId: DeviceId) {
        systemConnectionRequest?.cancel()
        systemConnectionRequest = controlScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The request arrives while the launching activity is not started yet; the claim bridges that gap
            // and ends with the call, by which time the started interface holds the session itself.
            val hold = runtime.holdOrdinarySession()
            try {
                val result = runtime.connectFromSystem(deviceId)
                if (result is OperationResult.Failure) model.notice.value = "无法打开所选耳机：${result.error.message}"
            } finally {
                hold.release()
            }
        }
    }

    /**
     * Moves the single control session to [way]; the outcome is visible in the runtime state's owner.
     * Whatever the outcome, wake-up observation and the background service follow the saved choice after.
     */
    fun selectControlWay(way: ControlWay) {
        controlScope.launch {
            runtime.selectControlWay(way)
            background.followSavedIntent()
        }
    }

    fun updateIntegrationSettings(settings: IntegrationSettings) {
        if (!permissions.unlocked) return
        val backgroundIntentChanged = runtime.integration.value.settings.backgroundControlEnabled != settings.backgroundControlEnabled
        // Only the explicit handoff changes the saved control way.
        runtime.updateIntegrationSettings(settings)
        // A stop choice must be on disk before the user can leave the application.
        settingsStore.saveHost(hostSettings(), commit = backgroundIntentChanged)
        moduleSettingsChanges.trySend(Unit)
        background.followSavedIntent()
    }

    private fun hostSettings() = HostSettings(runtime.state.value.settings, runtime.integration.value.settings)

    fun setBackgroundControlEnabled(enabled: Boolean) {
        updateIntegrationSettings(runtime.integration.value.settings.copy(backgroundControlEnabled = enabled))
    }

    /**
     * Entry of the system-delivered wake-ups (first unlock of a boot, an audio connection while the
     * process was gone, device presence). It only starts the service when ordinary control is the saved
     * choice and the restored saved intent, first unlock and Bluetooth permission allow it, so a disabled,
     * locked or module-controlled state is never revived. It creates no session itself: the service it
     * starts holds the ordinary session, and the session ends when the service does.
     */
    fun wakeOrdinaryControl() {
        loadUnlockedSettings()
        if (!background.ordinarySelected) return
        background.followSavedIntent()
    }

    fun refreshPlatform() {
        runtime.updatePlatformState(readPlatformState(permissions.bluetoothRequested, preferencesLoaded))
        runtime.updateIntegrationPlatform(
            readIntegrationPlatform(background.service, companionWake.facts()).copy(
                module = framework.availability(),
                bridge = framework.diagnostics(),
            ),
        )
    }

    private fun loadUnlockedSettings() {
        if (preferencesLoaded || !permissions.unlocked) return
        preferencesLoaded = true
        val saved = settingsStore.load()
        runtime.onUserUnlocked(saved.host)
        moduleSettingsChanges.trySend(Unit)
        mutableUiSettings.value = saved.ui
        refreshPlatform()
        background.syncObservation()
    }
}
