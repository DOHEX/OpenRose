package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.BridgeIpcState
import io.github.dohex.openrose.core.BridgeSessionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.IslandTrigger
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings

/** Settings content only. The application root owns the page title, navigation, and scrolling. */
@Composable
internal fun PreferencesPage(
    state: ControlState,
    onChange: (UserSettings) -> Unit,
) {
    val settings = state.settings
    var systemPreferenceFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.platform.systemIntegrationApplicability, state.platform.module) {
        systemPreferenceFeedback = null
    }
    val saveSystemPreference: (UserSettings) -> Unit = { nextSettings ->
        onChange(nextSettings)
        systemPreferenceFeedback = if (state.platform.module == ModuleAvailability.Available) {
            "偏好已保存。系统状态请查看系统页。"
        } else {
            "偏好已保存，待系统就绪后生效。"
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(
            title = "界面风格",
            footnote = "切换组件风格时，当前耳机控制会话保持运行。",
        ) {
            AppChoiceRow(
                title = "组件风格",
                valueText = interfaceStyleLabel(settings.interfaceStyle),
                options = listOf(
                    AppOption(InterfaceStyle.MIUIX, "Miuix"),
                    AppOption(InterfaceStyle.MATERIAL3, "Material 3"),
                ),
                selected = settings.interfaceStyle,
                onSelect = { onChange(settings.copy(interfaceStyle = it)) },
            )
        }

        AppGroup(title = "显示模式") {
            AppChoiceRow(
                title = "明暗模式",
                valueText = appearanceLabel(settings.appearance),
                options = Appearance.entries.map { AppOption(it, appearanceLabel(it)) },
                selected = settings.appearance,
                onSelect = { onChange(settings.copy(appearance = it)) },
            )
        }

        if (state.platform.hyperOsIntegrationApplicable) {
            AppGroup(
                title = "系统呈现",
                footnote = "官方沿用系统耳机呈现，模块使用 OpenRose 样式；两者都由系统超级岛展示。",
            ) {
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
            }

            if (settings.islandMode == IslandMode.MODULE) {
                AppGroup(
                    title = "模块显示时机",
                    footnote = "普通电量刷新不会重复触发。佩戴、摘下与入盒暂不开放。",
                ) {
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
                        ),
                        checked = settings.islandTriggers,
                        enabledFor = { true },
                        onToggle = { trigger ->
                            val triggers = if (trigger in settings.islandTriggers) {
                                settings.islandTriggers - trigger
                            } else {
                                settings.islandTriggers + trigger
                            }
                            saveSystemPreference(settings.copy(islandTriggers = triggers))
                        },
                    )
                }
            }

            systemPreferenceFeedback?.let { message ->
                AppText(message, Modifier.padding(horizontal = 16.dp), role = AppTextRole.Summary)
            }
        }

        if (state.platform.controlTransportApplicability == PlatformApplicability.APPLICABLE) {
            AppGroup(
                title = "连接偏好",
                footnote = "默认自动选择适用的连接方式；无法确认连接条件时使用 BLE。当前连接和正在执行的设置会保留。",
            ) {
                AppSwitchRow(
                    title = "强制使用 BLE",
                    summary = "仅影响下一次连接",
                    checked = settings.forceBle,
                    onChange = { onChange(settings.copy(forceBle = it)) },
                )
            }
        }

        AppGroup(title = "关于") {
            AppInfoRow(
                label = "关于 OpenRose",
                value = "ROSE / ROSESELSA 耳机控制",
            )
        }
    }
}

/** Current platform status, applicable system-integration status, and diagnostics entry. */
@Composable
internal fun SystemIntegrationPage(
    state: ControlState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val hyperOsApplicable = state.platform.hyperOsIntegrationApplicable
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "系统状态") {
            PlatformStatusRows(state.platform, onRequestPermission, onOpenSystemSettings)
        }

        if (hyperOsApplicable) {
            IntegrationReadinessCard(state.platform.module)
        }

        AppGroup {
            AppArrowRow(
                title = "运行状态与诊断",
                summary = if (hyperOsApplicable) {
                    "系统入口、跨进程通道与呈现记录"
                } else {
                    "查看本平台状态与诊断信息"
                },
                value = if (!hyperOsApplicable || state.platform.module == ModuleAvailability.Available) "查看" else "待就绪",
                icon = RoseIcon.LOG,
                onClick = onDiagnostics,
            )
        }
    }
}

/** Platform diagnostics display only facts reported by the platform adapter. */
@Composable
internal fun PlatformDiagnosticsCard(
    platform: PlatformState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "系统状态") {
            PlatformStatusRows(platform, onRequestPermission, onOpenSystemSettings)
            if (platform.hyperOsIntegrationApplicable) {
                val moduleValue = when (val module = platform.module) {
                    ModuleAvailability.Available -> "已连接系统模块"
                    ModuleAvailability.NotEnabled -> "系统模块未启用"
                    is ModuleAvailability.Unavailable -> module.reason
                }
                val moduleTone = when (platform.module) {
                    ModuleAvailability.Available -> StatusTone.GOOD
                    ModuleAvailability.NotEnabled -> StatusTone.CAUTION
                    is ModuleAvailability.Unavailable -> StatusTone.NEUTRAL
                }
                ToneInfoRow("系统模块", moduleValue, moduleTone)
            }
        }

        if (platform.hyperOsIntegrationApplicable) {
            val bridge = platform.bridge
            if (bridge == null) {
                AppGroup(title = "系统桥接") {
                    AppText("尚无系统桥接诊断数据。", role = AppTextRole.Summary)
                }
            } else {
                AppGroup(
                    title = "系统桥接",
                    footnote = "桥接诊断描述已观察到的系统入口和调用状态，不代表系统界面已实际显示。",
                ) {
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
                        "android" -> "系统提示服务"
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
                    AppGroup(
                        title = "系统呈现记录",
                        footnote = "记录表示已请求的系统操作，不代表屏幕上的实际效果已通过验收。",
                    ) {
                        presentation.entries.forEach { (feature, result) ->
                            DiagnosticPresentationRow(presentationLabel(feature), result)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformStatusRows(
    platform: PlatformState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    AppInfoRow("当前平台", hostPlatformLabel(platform.hostPlatform))
    if (platform.hostPlatform == HostPlatform.ANDROID) {
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
        if (platform.permission == PermissionState.REQUIRED || platform.permission == PermissionState.DENIED) {
            AppButton(
                text = "授予蓝牙权限",
                onClick = onRequestPermission,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (platform.systemIntegrationApplicability == PlatformApplicability.UNKNOWN) {
            ToneInfoRow("系统集成适用性", "尚未核实", StatusTone.CAUTION)
        }
    }

    val transportValue = when (val transport = platform.transport) {
        TransportAvailability.Available -> "可用"
        TransportAvailability.Disabled -> "系统蓝牙已关闭"
        is TransportAvailability.Unavailable -> transport.reason
    }
    val transportTone = when (platform.transport) {
        TransportAvailability.Available -> StatusTone.GOOD
        TransportAvailability.Disabled -> StatusTone.CAUTION
        is TransportAvailability.Unavailable -> StatusTone.NEUTRAL
    }
    ToneInfoRow("蓝牙传输", transportValue, transportTone)
    if (platform.hostPlatform == HostPlatform.ANDROID && platform.transport == TransportAvailability.Disabled) {
        AppButton(
            text = "打开蓝牙设置",
            onClick = onOpenSystemSettings,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun IntegrationReadinessCard(module: ModuleAvailability) {
    val (title, summary, tone) = when (module) {
        ModuleAvailability.Available -> Triple(
            "系统集成已连接",
            "状态来自当前系统适配器。",
            StatusTone.GOOD,
        )
        ModuleAvailability.NotEnabled -> Triple(
            "系统集成未就绪",
            "系统模块尚未启用。",
            StatusTone.CAUTION,
        )
        is ModuleAvailability.Unavailable -> Triple(
            "系统集成未就绪",
            "系统集成暂不可用。${module.reason}",
            StatusTone.CAUTION,
        )
    }
    AppGroup {
        ToneInfoRow("就绪状态", title, tone)
        AppText(summary, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), role = AppTextRole.Summary)
    }
}

@Composable
private fun ToneInfoRow(label: String, value: String, tone: StatusTone) {
    AppInfoRow(label = label, value = value, valueColor = tone.color())
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

private enum class StatusTone {
    NEUTRAL,
    GOOD,
    CAUTION,
    ERROR;

    @Composable
    fun color(): Color = appColor(
        when (this) {
            NEUTRAL, CAUTION -> AppColorRole.Summary
            GOOD -> AppColorRole.Primary
            ERROR -> AppColorRole.Error
        },
    )
}

private fun interfaceStyleLabel(style: InterfaceStyle): String = when (style) {
    InterfaceStyle.MIUIX -> "Miuix"
    InterfaceStyle.MATERIAL3 -> "Material 3"
}

private fun hostPlatformLabel(platform: HostPlatform): String = when (platform) {
    HostPlatform.UNKNOWN -> "平台未核实"
    HostPlatform.ANDROID -> "Android"
    HostPlatform.WINDOWS -> "Windows"
    HostPlatform.LINUX -> "Linux"
}

private val PlatformState.hyperOsIntegrationApplicable: Boolean
    get() = hostPlatform == HostPlatform.ANDROID &&
        systemIntegrationApplicability == PlatformApplicability.APPLICABLE

private fun appearanceLabel(appearance: Appearance): String = when (appearance) {
    Appearance.SYSTEM -> "跟随系统"
    Appearance.LIGHT -> "浅色"
    Appearance.DARK -> "深色"
}

private fun islandModeLabel(mode: IslandMode): String = when (mode) {
    IslandMode.OFF -> "不显示"
    IslandMode.OFFICIAL -> "官方"
    IslandMode.MODULE -> "模块"
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
    "OFFICIAL_GUARD" -> "提示归属保护"
    "OFFICIAL_CONNECTION" -> "官方连接提示"
    else -> feature
}

private fun presentationLabel(feature: String): String = when (feature) {
    "OFFICIAL_CONNECTION" -> "官方连接提示"
    "MODULE_CONNECTION" -> "模块连接提示"
    "STATUS_NOTIFICATION" -> "耳机状态通知"
    else -> feature
}
