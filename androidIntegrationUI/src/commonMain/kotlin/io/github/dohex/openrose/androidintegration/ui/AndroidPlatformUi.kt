package io.github.dohex.openrose.androidintegration.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.androidintegration.*
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartPhase
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartResult
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartStatus
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartUpdate
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AndroidPlatformUi(
    private val integration: StateFlow<IntegrationState>,
    private val onIntegrationSettingsChange: (IntegrationSettings) -> Unit,
    private val onSelectControlWay: (ControlWay) -> Unit,
    override val requestBluetoothPermission: () -> Unit,
    override val openBluetoothSettings: () -> Unit,
    private val onRequestNotificationPermission: () -> Unit,
    private val onRequestAssociation: () -> Unit,
    private val onOpenAppSettings: () -> Unit,
    private val onRestartScope: suspend (List<String>, (ScopeRestartUpdate) -> Unit) -> List<ScopeRestartResult>,
    private val restartScope: CoroutineScope,
) : PlatformUi {
    override val platformName: String = "Android"
    private var restartRunning by mutableStateOf(false)

    // Whether the restart dialog is shown; its content stays until the next opening so the dialog can animate out.
    private var restartDialogOpen by mutableStateOf(false)
    private var restartSelection by mutableStateOf(INTEGRATION_SCOPE_PACKAGES.toSet())
    private var restartTargets by mutableStateOf(emptyList<String>())
    private var restartResults by mutableStateOf<List<ScopeRestartResult>?>(null)
    private var restartPhase by mutableStateOf<ScopeRestartPhase?>(null)

    override val dynamicColor: Boolean
        @Composable get() {
            val integration by integration.collectAsState()
            return integration.settings.dynamicColor &&
                integration.platform.dynamicColorApplicability == PlatformApplicability.APPLICABLE
        }

    @Composable
    override fun BluetoothPermissionRows(platform: PlatformState, diagnostics: Boolean) {
        val integration by integration.collectAsState()
        if (diagnostics) {
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
        }
        if (platform.permission == PermissionState.REQUIRED || platform.permission == PermissionState.DENIED) {
            if (!diagnostics) {
                val denied = platform.permission == PermissionState.DENIED
                ToneInfoRow("蓝牙权限", if (denied) "未获得蓝牙权限" else "需要授权后才能使用蓝牙", if (denied) StatusTone.ERROR else StatusTone.CAUTION)
            }
            AppButton(text = "授予蓝牙权限", onClick = requestBluetoothPermission, modifier = Modifier.padding(start = 16.dp))
        }
        if (integration.platform.systemIntegrationApplicability == PlatformApplicability.UNKNOWN) {
            ToneInfoRow("系统集成适用性", "尚未核实", StatusTone.CAUTION)
        }
    }

    @Composable
    override fun StatusSections(state: ControlState) {
        val integration by integration.collectAsState()
        if (hyperOsIntegrationApplicable(integration.platform)) IntegrationReadinessCard(integration.platform.module)
        ControlStatusGroup(state, integration)
    }

    @Composable
    override fun SettingsSections(state: ControlState, position: PlatformSettingsPosition) {
        val integration by integration.collectAsState()
        val settings = integration.settings
        if (position == PlatformSettingsPosition.BEFORE_CONNECTION) {
            ControlWayGroup(integration, onSelectControlWay)
            return
        }
        var systemPreferenceFeedback by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(integration.platform.systemIntegrationApplicability, integration.platform.module) {
            systemPreferenceFeedback = null
        }
        val saveSystemPreference: (IntegrationSettings) -> Unit = { nextSettings ->
            onIntegrationSettingsChange(nextSettings)
            systemPreferenceFeedback = if (integration.platform.module == ModuleAvailability.Available) {
                "偏好已保存。系统状态请查看状态页。"
            } else {
                "偏好已保存，待系统就绪后生效。"
            }
        }
        if (state.backgroundControlApplicable(integration)) {
            BackgroundControlGroup(
                state,
                integration,
                onIntegrationSettingsChange,
                requestBluetoothPermission,
                onRequestNotificationPermission,
                onRequestAssociation,
                onOpenAppSettings,
            )
        }
        if (hyperOsIntegrationApplicable(integration.platform)) {
            AppGroup(title = "系统呈现") {
                AppChoiceRow(
                    title = "超级岛",
                    valueText = islandModeLabel(settings.islandMode),
                    options = IslandMode.entries.map { AppOption(it, islandModeLabel(it)) },
                    selected = settings.islandMode,
                    onSelect = { saveSystemPreference(settings.copy(islandMode = it)) },
                )
                AppSwitchRow(
                    title = "焦点通知",
                    summary = "连接期间保留耳机状态通知",
                    checked = settings.focusNotificationEnabled,
                    onChange = { saveSystemPreference(settings.copy(focusNotificationEnabled = it)) },
                )
                if (settings.focusNotificationEnabled) {
                    AppChoiceRow(
                        title = "点击通知打开",
                        valueText = statusNotificationTargetLabel(settings.statusNotificationTarget),
                        options = StatusNotificationTarget.entries.map { AppOption(it, statusNotificationTargetLabel(it)) },
                        selected = settings.statusNotificationTarget,
                        onSelect = { saveSystemPreference(settings.copy(statusNotificationTarget = it)) },
                    )
                }
            }

            if (settings.islandMode == IslandMode.MODULE) {
                AppGroup(title = "模块显示时机") {
                    if (settings.islandTriggers.isEmpty()) {
                        AppText("未选择显示时机，不自动触发。", role = AppTextRole.Summary)
                    }
                    AppCheckGroup(
                        options = listOf(
                            AppOption(
                                value = IslandTrigger.CONNECTION,
                                label = "连接时",
                                contentDescription = "新连接会话开始时显示连接提示",
                            ),
                            AppOption(
                                value = IslandTrigger.IN_CASE,
                                label = "入盒时",
                                contentDescription = "耳机放进充电盒时显示入盒提示",
                            ),
                        ),
                        checked = settings.islandTriggers,
                        enabledFor = { true },
                        onToggle = { trigger ->
                            val triggers = if (trigger in settings.islandTriggers) {
                                settings.islandTriggers.removing(trigger)
                            } else {
                                settings.islandTriggers.adding(trigger)
                            }
                            saveSystemPreference(settings.copy(islandTriggers = triggers))
                        },
                    )
                    AppText("入盒时仅对 $inCaseModels 生效。", role = AppTextRole.Summary)
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
                    onSelect = { saveSystemPreference(settings.copy(fusionSettingsDestination = it)) },
                )
                AppSwitchRow(
                    title = "关闭降噪使用抗风噪",
                    summary = "点“关闭”时改用抗风噪模式；仅支持抗风噪的型号生效，其余仍为标准模式",
                    checked = settings.fusionOffAsWind,
                    onChange = { saveSystemPreference(settings.copy(fusionOffAsWind = it)) },
                )
            }

            systemPreferenceFeedback?.let { message ->
                AppText(message, Modifier.padding(horizontal = 16.dp), role = AppTextRole.Summary)
            }
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

                bridge.endpoints.values.forEach { endpoint ->
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
        AppGroup {
            AppArrowRow(
                title = "诊断与记录",
                summary = if (applicable) "系统入口、跨进程通道与呈现记录" else "查看本平台状态与诊断信息",
                value = if (!applicable || integration.platform.module == ModuleAvailability.Available) "查看" else "待就绪",
                icon = RoseIcon.LOG,
                onClick = onDiagnostics,
            )
        }
    }

    @Composable
    override fun StatusActions() {
        ScopeRestartAction(
            open = restartDialogOpen,
            running = restartRunning,
            selected = restartSelection,
            targets = restartTargets,
            phase = restartPhase,
            results = restartResults,
            onOpen = {
                restartResults = null
                restartSelection = INTEGRATION_SCOPE_PACKAGES.toSet()
                restartDialogOpen = true
            },
            onToggle = { target ->
                restartSelection = if (target in restartSelection) restartSelection - target else restartSelection + target
            },
            onConfirm = {
                val targets = INTEGRATION_SCOPE_PACKAGES.filter { it in restartSelection }
                if (!restartRunning && targets.isNotEmpty()) {
                    restartRunning = true
                    restartTargets = targets
                    restartResults = emptyList()
                    restartPhase = ScopeRestartPhase.Authorizing
                    restartScope.launch {
                        try {
                            restartResults = onRestartScope(targets) { update ->
                                when (update) {
                                    is ScopeRestartPhase -> restartPhase = update
                                    is ScopeRestartResult -> restartResults = restartResults.orEmpty() + update
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val done = restartResults.orEmpty().associateBy { it.target }
                            restartResults = targets.map {
                                done[it] ?: ScopeRestartResult(it, ScopeRestartStatus.FAILED, failure.message ?: "操作失败")
                            }
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
}

private fun hyperOsIntegrationApplicable(integration: IntegrationPlatformState): Boolean = integration
    .systemIntegrationApplicability == PlatformApplicability.APPLICABLE

@Composable
private fun IntegrationReadinessCard(module: ModuleAvailability) {
    val clause = module.unreadyClause()
    when {
        clause == null -> AppStatusCard(AppStatusKind.Good, RoseIcon.CHECK, "系统集成已连接", null, null)

        module == ModuleAvailability.Unresponsive ->
            AppStatusCard(AppStatusKind.Error, RoseIcon.CLOSE, "系统集成无响应", "$clause。", module.nextStep())

        else -> AppStatusCard(AppStatusKind.Caution, RoseIcon.INFO, "系统集成未就绪", "$clause。", module.nextStep())
    }
}

@Composable
private fun DiagnosticResultRow(label: String, result: String) {
    val code = result.substringBefore(" · ")
    val status = when (code) {
        "RESOLVED" -> "已定位入口，实际行为待验收"
        "IDENTIFIED" -> "已匹配当前设备"
        "SUBMITTED" -> "已提交操作，等待设备确认"
        "CONFIRMED" -> "设备已确认操作"
        "CALLED" -> "已调用接口，实际行为待验收"
        "REJECTED" -> "请求已拒绝"
        "UNSUPPORTED" -> "缺少已核实的能力映射"
        "UNAVAILABLE" -> "当前系统路径不可用"
        "ACTION_FAILED" -> "操作失败"
        else -> "等待确认"
    }
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
    IslandMode.OFFICIAL -> "官方"
    IslandMode.MODULE -> "模块"
}

private fun fusionSettingsDestinationLabel(destination: FusionSettingsDestination): String = when (destination) {
    FusionSettingsDestination.SYSTEM -> "系统耳机详情页"
    FusionSettingsDestination.OPENROSE -> "OpenRose 耳机主页"
}

private fun statusNotificationTargetLabel(target: StatusNotificationTarget): String = when (target) {
    StatusNotificationTarget.OPENROSE -> "OpenRose 耳机主页"
    StatusNotificationTarget.SYSTEM -> "系统耳机详情页"
    StatusNotificationTarget.ROSELINK -> "RoseLink"
}

private val inCaseModels: String = ModelProfile.entries.filter { it.reportsInCase }.joinToString("、") { it.displayName }

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
    "OFFICIAL_CONNECTION" -> "官方连接提示"
    else -> feature
}

private fun presentationLabel(feature: String): String = when (feature) {
    "OFFICIAL_CONNECTION" -> "官方连接提示"
    "MODULE_CONNECTION" -> "模块连接提示"
    "STATUS_NOTIFICATION" -> "耳机状态通知"
    else -> feature
}
