package io.github.dohex.openrose.androidintegration.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.androidintegration.*
import io.github.dohex.openrose.androidintegration.scope.INTEGRATION_SCOPE_PACKAGES
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartPhase
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartResult
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartStatus
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartUpdate
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AndroidPlatformUi(
    private val integration: StateFlow<IntegrationState>,
    private val onIntegrationSettingsChange: (IntegrationSettings) -> Unit,
    private val onSelectControlWay: (ControlWay) -> Unit,
    requestBluetoothPermission: () -> Unit,
    override val openBluetoothSettings: () -> Unit,
    private val onRequestNotificationPermission: () -> Unit,
    private val onRequestAssociation: () -> Unit,
    private val onOpenAppSettings: () -> Unit,
    private val onRestartScope: suspend (List<String>, (ScopeRestartUpdate) -> Unit) -> List<ScopeRestartResult>,
    private val restartScope: CoroutineScope,
    private val circulationSettings: (@Composable () -> Unit)? = null,
    private val circulation: CirculationClient? = null,
) : PlatformUi {
    private val requestAppPermission = requestBluetoothPermission
    override val requestBluetoothPermission: (() -> Unit)?
        get() = requestAppPermission.takeIf { integration.value.owner.selected == ControlWay.APP }

    override val platformName: String = "Android"
    private var restartRunning by mutableStateOf(false)

    private var restartDialogOpen by mutableStateOf(false)
    private var restartSelection by mutableStateOf(emptySet<String>())
    private var restartTargets by mutableStateOf(emptyList<String>())
    private var restartResults by mutableStateOf<List<ScopeRestartResult>?>(null)
    private var restartPhase by mutableStateOf<ScopeRestartPhase?>(null)

    override val circulationPairingContent: (@Composable () -> Unit)?
        @Composable get() = circulationSettings.takeIf { integration.collectAsState().value.circulationAvailable }

    override val dynamicColor: Boolean
        @Composable get() = integration.collectAsState().value.dynamicColorInEffect

    override val headsetCirculation: CirculationClient?
        @Composable get() = circulation.takeIf { integration.collectAsState().value.circulationAvailable }

    @Composable
    override fun BluetoothPermissionRows(platform: PlatformState) {
        val integration by integration.collectAsState()
        val permissionValue = when (platform.permission) {
            PermissionState.NOT_REQUIRED -> "无需应用内授权"
            PermissionState.GRANTED -> "已授权"
            PermissionState.REQUIRED -> "需要授权后才能使用蓝牙"
            PermissionState.DENIED -> "未获得蓝牙权限"
        }
        val permissionTone = when (platform.permission) {
            PermissionState.NOT_REQUIRED -> StatusTone.NEUTRAL
            PermissionState.GRANTED -> StatusTone.GOOD
            PermissionState.REQUIRED -> StatusTone.CAUTION
            PermissionState.DENIED -> StatusTone.ERROR
        }
        ToneInfoRow("蓝牙权限", permissionValue, permissionTone)
        if (platform.permission.missing) {
            requestBluetoothPermission?.let { AppButton("授予蓝牙权限", it, modifier = Modifier.padding(start = 16.dp)) }
        }
        if (platform.scanPermission.missing) ScanPermissionRow()
        if (integration.platform.systemIntegrationApplicability == PlatformApplicability.UNKNOWN) {
            ToneInfoRow("系统集成适用性", "尚未核实", StatusTone.CAUTION)
        }
    }

    @Composable
    private fun ScanPermissionRow() {
        val request = requestBluetoothPermission
        val guidance = if (request == null) "模块宿主未获得扫描权限，可切回应用控制后搜索" else "仅搜索新耳机时需要，已配对耳机可直接连接"
        ToneInfoRow("扫描定位权限", guidance, StatusTone.CAUTION)
        request?.let { AppButton("授予定位权限", it, modifier = Modifier.padding(start = 16.dp)) }
    }

    @Composable
    override fun controlVerdict(state: ControlState): StatusVerdict? {
        val integration by integration.collectAsState()
        return ownerVerdict(integration.owner, integration.platform.module)
    }

    override val controlWayLabel: String
        @Composable get() = wayLabel(integration.collectAsState().value.owner.selected)

    @Composable
    override fun StatusSections(state: ControlState) {
        val integration by integration.collectAsState()
        AppStatusSection {
            when (integration.platform.systemIntegrationApplicability) {
                PlatformApplicability.APPLICABLE -> {
                    val (value, tone) = when (integration.platform.module) {
                        ModuleAvailability.Available -> "已连接" to StatusTone.GOOD
                        ModuleAvailability.Unresponsive -> "无响应" to StatusTone.ERROR
                        ModuleAvailability.NotEnabled, is ModuleAvailability.Unavailable -> "未就绪" to StatusTone.CAUTION
                    }
                    AppFactRow(RoseIcon.MODULE, "系统集成", value, tone)
                }

                PlatformApplicability.UNKNOWN -> AppFactRow(RoseIcon.MODULE, "系统集成适用性", "尚未核实", StatusTone.CAUTION)

                PlatformApplicability.NOT_APPLICABLE -> Unit
            }
            ControlStatusRows(integration.owner)
        }
    }

    override val hasControlSettings: Boolean
        @Composable get() = true

    override val systemIntegrationSummary: String?
        @Composable get() {
            val current by integration.collectAsState()
            if (!hyperOsIntegrationApplicable(current.platform)) return null
            return systemIntegrationSummary(current.settings)
        }

    override val systemIntegrationContent: (@Composable () -> Unit)?
        @Composable get() {
            val current by integration.collectAsState()
            if (!hyperOsIntegrationApplicable(current.platform)) return null
            return {
                val live by integration.collectAsState()
                SystemIntegrationSettingsContent(live, onIntegrationSettingsChange)
            }
        }

    @Composable
    override fun ControlSettingsRows(state: ControlState) {
        val integration by integration.collectAsState()
        ControlWayRows(integration, onSelectControlWay)
    }

    @Composable
    override fun BackgroundSettingsSection(state: ControlState) {
        val integration by integration.collectAsState()
        if (state.backgroundControlApplicable(integration)) {
            BackgroundControlGroup(
                state,
                integration,
                onIntegrationSettingsChange,
                requestAppPermission,
                onRequestNotificationPermission,
                onRequestAssociation,
                onOpenAppSettings,
            )
        }
    }

    @Composable
    override fun DisplayModeRows() {
        val integration by integration.collectAsState()
        if (integration.platform.dynamicColorApplicability != PlatformApplicability.APPLICABLE) return
        AppSwitchRow(
            title = "动态取色",
            summary = "跟随系统壁纸配色",
            checked = integration.settings.dynamicColor,
            onChange = { onIntegrationSettingsChange(integration.settings.copy(dynamicColor = it)) },
        )
    }

    @Composable
    override fun DiagnosticsStatusRows(state: ControlState) {
        val integration by integration.collectAsState()
        val platform = integration.platform
        if (hyperOsIntegrationApplicable(platform)) {
            val moduleValue = platform.module.unreadyClause() ?: "已连接系统模块"
            val moduleTone = when (platform.module) {
                ModuleAvailability.Available -> StatusTone.GOOD
                ModuleAvailability.NotEnabled -> StatusTone.CAUTION
                ModuleAvailability.Unresponsive -> StatusTone.ERROR
                is ModuleAvailability.Unavailable -> StatusTone.NEUTRAL
            }
            ToneInfoRow("系统模块", moduleValue, moduleTone)
        }
    }

    @Composable
    override fun DiagnosticsSections(state: ControlState) {
        val integration by integration.collectAsState()
        val platform = integration.platform
        if (hyperOsIntegrationApplicable(platform)) {
            val bridge = platform.bridge
            if (bridge == null) {
                AppGroup(title = "系统桥接") {
                    AppText("尚无系统桥接诊断数据。", role = AppTextRole.Summary)
                }
            } else {
                AppGroup(title = "系统桥接") {
                    bridge.frameworkName?.let { AppInfoRow("框架", it) }
                    bridge.frameworkVersion?.let { AppInfoRow("框架版本", it) }
                    bridge.frameworkApi?.let { AppInfoRow("框架 API", it.toString()) }
                    bridge.targetPackage?.let { AppInfoRow("目标包", it) }
                    bridge.targetProcess?.let { AppInfoRow("目标进程", it) }
                    bridge.componentVersion?.let { AppInfoRow("系统组件版本", it) }
                    bridge.componentMatch?.let { AppInfoRow("匹配依据", it) }
                    AppInfoRow("跨进程通道", bridgeIpcLabel(bridge.ipc))
                    AppInfoRow("系统入口读取", bridgeSessionLabel(bridge.session))
                    bridge.detail?.let { AppInfoRow("详情", it) }
                }

                bridge.endpoints.values.forEach { EndpointDiagnostics(it) }

                bridge.presentation.takeIf { it.isNotEmpty() }?.let { presentation ->
                    AppGroup(title = "系统呈现记录") {
                        presentation.entries.forEach { (feature, result) ->
                            DiagnosticPresentationRow(presentationLabel(feature), result)
                        }
                    }
                }
            }
        }
        ControlOwnerDiagnostics(integration)
    }

    @Composable
    override fun DiagnosticsEntry(onDiagnostics: () -> Unit) {
        val integration by integration.collectAsState()
        val applicable = hyperOsIntegrationApplicable(integration.platform)
        AppStatusSection {
            AppArrowRow(
                title = "诊断与记录",
                summary = if (applicable) "系统入口、跨进程通道与呈现记录" else "查看本平台状态与诊断信息",
                value = "待就绪".takeIf { applicable && integration.platform.module != ModuleAvailability.Available },
                icon = RoseIcon.LOG,
                onClick = onDiagnostics,
            )
        }
    }

    @Composable
    override fun StatusActions() {
        val current by integration.collectAsState()
        val hyperOs = hyperOsIntegrationApplicable(current.platform)
        val available = INTEGRATION_SCOPE_PACKAGES.filter { scope ->
            scope in current.platform.installedScopes &&
                (hyperOs || (current.owner.selected == ControlWay.MODULE && scope == "com.android.bluetooth"))
        }
        if (available.isEmpty()) return
        ScopeRestartAction(
            availableTargets = available,
            open = restartDialogOpen,
            running = restartRunning,
            selected = restartSelection.intersect(available.toSet()),
            targets = restartTargets,
            phase = restartPhase,
            results = restartResults,
            onOpen = {
                restartResults = null
                restartSelection = available.toSet()
                restartDialogOpen = true
            },
            onToggle = { target ->
                restartSelection = if (target in restartSelection) restartSelection - target else restartSelection + target
            },
            onConfirm = {
                val targets = available.filter { it in restartSelection }
                if (!restartRunning && targets.isNotEmpty()) {
                    restartRunning = true
                    restartTargets = targets
                    restartResults = emptyList()
                    restartPhase = ScopeRestartPhase.Authorizing
                    restartScope.launch {
                        try {
                            restartResults = restart(targets)
                        } finally {
                            restartRunning = false
                            restartPhase = null
                        }
                    }
                }
            },
            onClose = { restartDialogOpen = false },
        )
    }

    private suspend fun restart(targets: List<String>): List<ScopeRestartResult> {
        val outcome = runCatching {
            onRestartScope(targets) { update ->
                when (update) {
                    is ScopeRestartPhase -> restartPhase = update
                    is ScopeRestartResult -> restartResults = restartResults.orEmpty() + update
                }
            }
        }
        return outcome.getOrElse { failure ->
            if (failure !is Exception || failure is CancellationException) throw failure
            val done = restartResults.orEmpty().associateBy { it.target }
            targets.map { done[it] ?: ScopeRestartResult(it, ScopeRestartStatus.FAILED, failure.message ?: "操作失败") }
        }
    }
}

private fun hyperOsIntegrationApplicable(integration: IntegrationPlatformState): Boolean = integration
    .systemIntegrationApplicability == PlatformApplicability.APPLICABLE

@Composable
private fun DiagnosticResultRow(label: String, result: String) {
    val code = result.substringBefore(" · ")
    val status = diagnosticStatusLabels[code] ?: "等待确认"
    val detail = result.substringAfter(" · ", "").takeIf { it.isNotBlank() }
    val tone = when (code) {
        "RESOLVED", "IDENTIFIED", "CONFIRMED" -> StatusTone.GOOD
        "REJECTED", "ACTION_FAILED" -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }
    ToneInfoRow(label, status, tone)
    detail?.let { AppInfoRow("依据", it) }
}

@Composable
private fun DiagnosticPresentationRow(label: String, result: String) {
    val code = result.substringBefore(" · ")
    val status = when (code) {
        "CALLED" -> "已请求系统呈现，实际显示待验收"

        "UNAVAILABLE" -> "当前系统路径不可用"

        "WITHDRAWN" -> "已请求撤销"

        "ACTION_FAILED" -> if (result.contains("control_receiver_unavailable")) {
            "通知查看可用，系统控制入口不可用"
        } else {
            "操作失败，请查看连接与设备状态"
        }

        else -> "等待确认"
    }
    val detail = result.substringAfter(" · ", "").takeIf { it.isNotBlank() }
    val tone = when (code) {
        "WITHDRAWN" -> StatusTone.GOOD
        "ACTION_FAILED" -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }
    ToneInfoRow(label, status, tone)
    detail?.let { AppInfoRow("依据", it) }
}

private fun islandModeLabel(mode: IslandMode): String = when (mode) {
    IslandMode.OFF -> "不显示"
    IslandMode.SYSTEM_STYLE -> "系统样式"
    IslandMode.OPENROSE_STYLE -> "OpenRose 样式"
}

private fun fusionSettingsDestinationLabel(destination: FusionSettingsDestination): String = when (destination) {
    FusionSettingsDestination.SYSTEM -> "系统耳机详情页"
    FusionSettingsDestination.OPENROSE -> "OpenRose 耳机主页"
}

private fun statusNotificationTargetLabel(target: StatusNotificationTarget): String = when (target) {
    StatusNotificationTarget.OPENROSE -> "OpenRose 耳机主页"
    StatusNotificationTarget.SYSTEM -> "系统耳机详情页"
    StatusNotificationTarget.ROSELINK -> "RoseLink"
    StatusNotificationTarget.QUICK_PANEL -> "快捷面板"
}

private fun bridgeIpcLabel(state: BridgeIpcState): String = when (state) {
    BridgeIpcState.DISCONNECTED -> "未连接"
    BridgeIpcState.WAITING -> "等待目标进程"
    BridgeIpcState.AUTHENTICATED -> "已通过身份校验"
    BridgeIpcState.TARGET_INCOMPATIBLE -> "目标组件不匹配"
    BridgeIpcState.REJECTED -> "请求未通过校验"
}

private fun bridgeSessionLabel(state: BridgeSessionState): String = when (state) {
    BridgeSessionState.NONE -> "没有就绪设备会话"
    BridgeSessionState.WAITING_CONFIRMATION -> "已生成读取结果，等待目标进程确认"
    BridgeSessionState.READY -> "已读取当前会话"
    BridgeSessionState.STALE -> "旧引用已拒绝，等待当前会话"
}

private fun featureLabel(feature: String): String = when (feature) {
    "SYSTEM_HEADSET" -> "原生耳机接入"
    "FUSION_CENTER" -> "融合状态与操作"
    "SYSTEM_CONNECTION" -> "系统样式连接提示"
    else -> feature
}

private fun presentationLabel(feature: String): String = when (feature) {
    "SYSTEM_CONNECTION" -> "系统样式连接提示"
    "OPENROSE_CONNECTION" -> "OpenRose 样式连接提示"
    "STATUS_NOTIFICATION" -> "耳机状态通知"
    else -> feature
}

@Composable
private fun EndpointDiagnostics(endpoint: SystemEndpointDiagnostics) {
    val endpointName = when (endpoint.targetPackage) {
        "com.android.bluetooth" -> "蓝牙服务"
        "com.xiaomi.bluetooth" -> "小米蓝牙"
        "com.milink.service" -> "融合设备中心"
        else -> endpoint.targetPackage
    }
    AppGroup(title = endpointName) {
        AppInfoRow("目标包", endpoint.targetPackage)
        AppInfoRow("目标进程", endpoint.targetProcess)
        AppInfoRow("组件版本", endpoint.componentVersion)
        AppInfoRow("入口依据", endpoint.entryMatch)
        AppInfoRow("跨进程通道", bridgeIpcLabel(endpoint.ipc))
        endpoint.features.forEach { (feature, result) ->
            DiagnosticResultRow(featureLabel(feature), result)
        }
    }
}

private val diagnosticStatusLabels = mapOf(
    "RESOLVED" to "已定位入口，实际行为待验收",
    "IDENTIFIED" to "已匹配当前设备",
    "SUBMITTED" to "已提交操作，等待设备确认",
    "CONFIRMED" to "设备已确认操作",
    "CALLED" to "已调用接口，实际行为待验收",
    "REJECTED" to "请求已拒绝",
    "UNSUPPORTED" to "缺少已核实的能力映射",
    "UNAVAILABLE" to "当前系统路径不可用",
    "ACTION_FAILED" to "操作失败",
)

@Composable
private fun SystemIntegrationSettingsContent(
    integration: IntegrationState,
    onIntegrationSettingsChange: (IntegrationSettings) -> Unit,
) {
    val settings = integration.settings
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "超级岛与通知") {
            AppChoiceRow(
                title = "超级岛",
                valueText = islandModeLabel(settings.islandMode),
                options = IslandMode.entries.map { AppOption(it, islandModeLabel(it)) },
                selected = settings.islandMode,
                onSelect = { onIntegrationSettingsChange(settings.copy(islandMode = it)) },
            )
            AppSwitchRow(
                title = "耳机状态通知",
                summary = "连接期间保留耳机状态卡片",
                checked = settings.statusNotificationEnabled,
                onChange = { onIntegrationSettingsChange(settings.copy(statusNotificationEnabled = it)) },
            )
            if (settings.statusNotificationEnabled && settings.islandMode == IslandMode.OPENROSE_STYLE) {
                AppSwitchRow(
                    title = "常驻电量岛",
                    summary = "连接期间超级岛一直显示电量；关闭后只在显示时机弹出几秒",
                    checked = settings.islandSummaryEnabled,
                    onChange = { onIntegrationSettingsChange(settings.copy(islandSummaryEnabled = it)) },
                )
            }
            if (settings.statusNotificationEnabled) {
                val unavailable = integration.platform.unavailableStatusTargets
                val selectedTarget = settings.statusNotificationTarget
                AppChoiceRow(
                    title = "点击通知打开",
                    valueText = unavailable[selectedTarget]
                        ?.let { "${statusNotificationTargetLabel(selectedTarget)}（$it，点击通知将打开 OpenRose）" }
                        ?: statusNotificationTargetLabel(selectedTarget),
                    options = StatusNotificationTarget.entries.map {
                        AppOption(it, statusNotificationTargetLabel(it), disabledReason = unavailable[it])
                    },
                    selected = selectedTarget,
                    onSelect = { onIntegrationSettingsChange(settings.copy(statusNotificationTarget = it)) },
                )
            }
        }

        if (settings.islandMode == IslandMode.OPENROSE_STYLE) {
            val footnote = listOfNotNull(
                "未选择显示时机，不会弹出展开。".takeIf { settings.islandTimings.isEmpty() },
                "入盒时仅对支持上报盒内状态的型号生效。",
            ).joinToString("\n")
            AppGroup(title = "显示时机", footnote = footnote) {
                AppCheckGroup(
                    options = listOf(
                        AppOption(
                            value = IslandTiming.CONNECTION,
                            label = "连接时",
                            contentDescription = "新连接会话开始时显示连接提示",
                        ),
                        AppOption(
                            value = IslandTiming.IN_CASE,
                            label = "入盒时",
                            contentDescription = "耳机放进充电盒时显示入盒提示",
                        ),
                    ),
                    checked = settings.islandTimings,
                    enabledFor = { true },
                    onToggle = { timing ->
                        val timings = if (timing in settings.islandTimings) {
                            settings.islandTimings.removing(timing)
                        } else {
                            settings.islandTimings.adding(timing)
                        }
                        onIntegrationSettingsChange(settings.copy(islandTimings = timings))
                    },
                )
            }
        }

        AppGroup(title = "融合设备中心") {
            AppChoiceRow(
                title = "附加设置入口",
                valueText = fusionSettingsDestinationLabel(settings.fusionSettingsDestination),
                options = FusionSettingsDestination.entries.map {
                    AppOption(it, fusionSettingsDestinationLabel(it))
                },
                selected = settings.fusionSettingsDestination,
                onSelect = { onIntegrationSettingsChange(settings.copy(fusionSettingsDestination = it)) },
            )
            AppSwitchRow(
                title = "关闭降噪使用抗风噪",
                summary = "点“关闭”时改用抗风噪模式；仅支持抗风噪的型号生效，其余仍为标准模式",
                checked = settings.fusionOffAsWind,
                onChange = { onIntegrationSettingsChange(settings.copy(fusionOffAsWind = it)) },
            )
        }
    }
}

private fun systemIntegrationSummary(settings: IntegrationSettings): String {
    val island = "超级岛：${islandModeLabel(settings.islandMode)}"
    val statusNotification = if (settings.statusNotificationEnabled) "耳机状态通知已开启" else "耳机状态通知已关闭"
    return "$island · $statusNotification"
}
