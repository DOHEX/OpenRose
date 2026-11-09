package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.androidintegration.BackgroundControlBlock
import io.github.dohex.openrose.androidintegration.BackgroundServiceFact
import io.github.dohex.openrose.androidintegration.BackgroundServiceFacts
import io.github.dohex.openrose.androidintegration.BridgeIpcState
import io.github.dohex.openrose.androidintegration.BridgeSessionState
import io.github.dohex.openrose.androidintegration.CompanionAssociation
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.ModuleUnavailableCause
import io.github.dohex.openrose.androidintegration.OwnerFailureStage
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.OwnerReadiness
import io.github.dohex.openrose.androidintegration.OwnerState
import io.github.dohex.openrose.androidintegration.PresenceObservation
import io.github.dohex.openrose.androidintegration.RunningNotification
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.androidintegration.SystemBridgeDiagnostics
import io.github.dohex.openrose.androidintegration.WakeBand
import io.github.dohex.openrose.androidintegration.WakeFacts
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartPhase
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartResult
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartStatus
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartUpdate
import io.github.dohex.openrose.androidintegration.ui.AndroidPlatformUi
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.desktop.DesktopPlatformUi
import io.github.dohex.openrose.desktop.DesktopSettings
import io.github.dohex.openrose.desktop.LoginLaunchControl
import io.github.dohex.openrose.desktop.LoginLaunchState
import io.github.dohex.openrose.desktop.TrayAvailability
import io.github.dohex.openrose.ui.PlatformUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val APP_PACKAGE = "io.github.dohex.openrose"
private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
private const val OWNER_SWITCH_MILLIS = 800L
private const val RESTART_STEP_MILLIS = 500L

/**
 * The real platform sections of each host, fed from the scenario instead of a platform. A scenario change
 * rebuilds every platform fact; what the user saves through these sections lasts until the next change.
 */
internal class SandboxPlatforms(private val model: SandboxModel, private val scope: CoroutineScope) {
    private val integration = MutableStateFlow(IntegrationState())
    private val desktopSettings = MutableStateFlow(DesktopSettings())
    private val tray = MutableStateFlow<TrayAvailability>(TrayAvailability.Available)
    private val loginLaunch = SandboxLoginLaunchControl(model)

    private val android = AndroidPlatformUi(
        integration = integration,
        onIntegrationSettingsChange = { settings -> integration.update { it.copy(settings = settings) } },
        onSelectControlWay = ::selectControlWay,
        requestBluetoothPermission = { resolve(SandboxPlatformIssue.PERMISSION_DENIED) },
        openBluetoothSettings = { resolve(SandboxPlatformIssue.BLUETOOTH_OFF) },
        onRequestNotificationPermission = { updateService { it.copy(notification = RunningNotification.VISIBLE) } },
        onRequestAssociation = {
            updateService {
                it.copy(wake = it.wake.copy(association = CompanionAssociation.ACTIVE, presence = PresenceObservation.OBSERVING))
            }
        },
        onOpenAppSettings = {},
        onRestartScope = ::restartScope,
        restartScope = scope,
    )
    private val desktops = mutableMapOf<String, PlatformUi>()

    init {
        scope.launch { model.scenario.collect(::apply) }
    }

    fun forScenario(scenario: SandboxScenario): PlatformUi = when (scenario.host) {
        HostPlatform.ANDROID -> android

        HostPlatform.WINDOWS, HostPlatform.LINUX, HostPlatform.UNKNOWN -> desktops.getOrPut(scenario.platformName) {
            DesktopPlatformUi(
                platformName = scenario.platformName,
                desktopSettings = desktopSettings,
                onDesktopSettingsChange = { desktopSettings.value = it },
                tray = tray,
                loginLaunch = loginLaunch,
            )
        }
    }

    private fun apply(scenario: SandboxScenario) {
        val selected = integration.value.settings.controlWay
        integration.update { current ->
            current.copy(
                platform = IntegrationPlatformState(
                    module = scenario.module.availability(),
                    bridge = scenario.module.bridge(),
                    systemIntegrationApplicability = if (scenario.hyperOs) {
                        PlatformApplicability.APPLICABLE
                    } else {
                        PlatformApplicability.NOT_APPLICABLE
                    },
                    backgroundService = scenario.backgroundService.facts(),
                ),
                owner = ownerState(selected, scenario.owner),
            )
        }
        tray.value = when (scenario.tray) {
            SandboxTray.CHECKING -> TrayAvailability.Checking
            SandboxTray.AVAILABLE -> TrayAvailability.Available
            SandboxTray.UNAVAILABLE -> TrayAvailability.Unavailable("当前桌面没有托盘宿主（GNOME 需启用 AppIndicator 扩展）")
        }
        loginLaunch.show(scenario.loginLaunch)
    }

    /** A handoff passes through Switching, then lands where the scenario says the new owner ends up. */
    private fun selectControlWay(way: ControlWay) {
        val from = integration.value.owner.selected
        integration.update {
            it.copy(
                settings = it.settings.copy(controlWay = way),
                owner = it.owner.copy(selected = way, readiness = OwnerReadiness.Switching(SwitchRequest("sandbox", from, way))),
            )
        }
        scope.launch {
            delay(OWNER_SWITCH_MILLIS)
            integration.update { it.copy(owner = ownerState(way, model.scenario.value.owner, from)) }
        }
    }

    private suspend fun restartScope(targets: List<String>, report: (ScopeRestartUpdate) -> Unit): List<ScopeRestartResult> {
        for (phase in restartPhases) {
            report(phase)
            delay(RESTART_STEP_MILLIS)
        }
        return targets.map { ScopeRestartResult(it, ScopeRestartStatus.RESTARTED).also(report) }
    }

    /** Stands for the user fixing [issue] through the platform action the page offers. */
    private fun resolve(issue: SandboxPlatformIssue) = model.updateScenario {
        if (it.issue == issue) it.copy(issue = SandboxPlatformIssue.NONE) else it
    }

    private fun updateService(change: (BackgroundServiceFacts) -> BackgroundServiceFacts) = integration.update {
        it.copy(platform = it.platform.copy(backgroundService = change(it.platform.backgroundService)))
    }
}

private val restartPhases = listOf(
    ScopeRestartPhase.Authorizing,
    ScopeRestartPhase.Signalling,
    ScopeRestartPhase.Waiting(2),
    ScopeRestartPhase.Waiting(1),
)

private class SandboxLoginLaunchControl(private val model: SandboxModel) : LoginLaunchControl {
    override val state = MutableStateFlow<LoginLaunchState>(LoginLaunchState.Reading)

    fun show(choice: SandboxLoginLaunch) {
        state.value = when (choice) {
            SandboxLoginLaunch.READING -> LoginLaunchState.Reading
            SandboxLoginLaunch.OFF -> LoginLaunchState.Ready(enabled = false)
            SandboxLoginLaunch.ON -> LoginLaunchState.Ready(enabled = true)
            SandboxLoginLaunch.DISABLED_BY_USER -> LoginLaunchState.DisabledByUser
            SandboxLoginLaunch.UNSUPPORTED -> LoginLaunchState.Unsupported("仅安装版可设置登录时启动")
        }
    }

    override fun set(enabled: Boolean) = model.updateScenario {
        it.copy(loginLaunch = if (enabled) SandboxLoginLaunch.ON else SandboxLoginLaunch.OFF)
    }

    /** Stands for the user allowing OpenRose again in the system's startup settings. */
    override fun openSystemSettings() = model.updateScenario { it.copy(loginLaunch = SandboxLoginLaunch.OFF) }
}

private fun ownerState(selected: ControlWay, owner: SandboxOwner, previous: ControlWay = selected.other()): OwnerState {
    val holder = { way: ControlWay ->
        val packageName = if (way == ControlWay.ORDINARY) APP_PACKAGE else BLUETOOTH_PACKAGE
        OwnerHolder(way, OwnerGeneration("sandbox", 1), packageName, packageName)
    }
    return when (owner) {
        SandboxOwner.READY -> OwnerState(selected, OwnerReadiness.Ready, holder(selected))

        SandboxOwner.STARTING -> OwnerState(selected, OwnerReadiness.Starting)

        SandboxOwner.UNAVAILABLE -> OwnerState(
            selected,
            OwnerReadiness.Unavailable(OwnerFailureStage.DISCOVERY, "沙盒：系统蓝牙没有提供模块控制端点"),
        )

        SandboxOwner.SWITCH_BLOCKED -> OwnerState(
            selected,
            OwnerReadiness.SwitchBlocked(
                SwitchRequest("sandbox", previous, selected),
                OwnerFailureStage.RELEASE,
                "沙盒：没有取得前一所有者的释放回执",
            ),
            holder(previous),
        )
    }
}

private fun ControlWay.other(): ControlWay = if (this == ControlWay.ORDINARY) ControlWay.MODULE else ControlWay.ORDINARY

private fun SandboxModule.availability(): ModuleAvailability = when (this) {
    SandboxModule.AVAILABLE -> ModuleAvailability.Available
    SandboxModule.NOT_ENABLED -> ModuleAvailability.NotEnabled
    SandboxModule.FRAMEWORK_MISSING -> ModuleAvailability.Unavailable("没有兼容框架服务连接到应用", ModuleUnavailableCause.FRAMEWORK_MISSING)
    SandboxModule.UNRESPONSIVE -> ModuleAvailability.Unresponsive
}

private fun SandboxModule.bridge(): SystemBridgeDiagnostics? = when (this) {
    SandboxModule.AVAILABLE -> SystemBridgeDiagnostics(
        frameworkName = "Sandbox framework",
        frameworkVersion = "sandbox",
        targetPackage = BLUETOOTH_PACKAGE,
        targetProcess = BLUETOOTH_PACKAGE,
        ipc = BridgeIpcState.AUTHENTICATED,
        session = BridgeSessionState.READY,
    )

    SandboxModule.UNRESPONSIVE -> SystemBridgeDiagnostics(
        frameworkName = "Sandbox framework",
        targetPackage = BLUETOOTH_PACKAGE,
        ipc = BridgeIpcState.WAITING,
        session = BridgeSessionState.STALE,
    )

    SandboxModule.NOT_ENABLED, SandboxModule.FRAMEWORK_MISSING -> null
}

/** Android 14 facts with the association still missing, so the association action shows until it is granted. */
private fun SandboxBackgroundService.facts(): BackgroundServiceFacts {
    val wake = WakeFacts(WakeBand.ANDROID_14_PLUS, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING)
    return when (this) {
        SandboxBackgroundService.NOT_RUNNING -> BackgroundServiceFacts(BackgroundServiceFact.NotRunning, RunningNotification.UNKNOWN, wake)

        SandboxBackgroundService.RUNNING -> BackgroundServiceFacts(BackgroundServiceFact.Running, RunningNotification.VISIBLE, wake)

        SandboxBackgroundService.START_REJECTED -> BackgroundServiceFacts(
            BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
            RunningNotification.HIDDEN,
            wake,
        )
    }
}
