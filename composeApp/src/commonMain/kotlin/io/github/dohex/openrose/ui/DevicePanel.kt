package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.*

internal fun ControlState.activeDevice(): DeviceState? = device?.takeIf { connection.matches(it) }
internal fun PlatformState.transportUnavailableForHome(): Boolean =
    permission in setOf(PermissionState.GRANTED, PermissionState.NOT_REQUIRED) &&
        transport is TransportAvailability.Unavailable
internal val ModelProfile.configurationLabel: String
    get() = when (this) { ModelProfile.I5_ZT -> "ZT"; ModelProfile.I7_RS_V2 -> "RS Common V2" }
internal val ModelProfile.shortLabel: String
    get() = when (this) { ModelProfile.I5_ZT -> "i5"; ModelProfile.I7_RS_V2 -> "i7" }

internal fun ControlState.controlTransportLabel(): String = when (transportSelection?.endpoint) {
    is TransportEndpoint.BleGatt -> "BLE"
    is TransportEndpoint.SppRfcomm -> "蓝牙串口（SPP）"
    null -> "尚未选择"
}

@Composable
internal fun HeadphonesPage(
    state: ControlState,
    onNavigate: (AppPage) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val device = state.activeDevice()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (device == null) {
            UnconnectedHeadphonesDashboard(
                state = state,
                onNavigate = onNavigate,
                onRequestPermission = onRequestPermission,
                onOpenSystemSettings = onOpenSystemSettings,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onStopScan = onStopScan,
            )
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 680.dp
            val deviceCard: @Composable () -> Unit = {
                AppDashboardCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EarbudsArtwork(Modifier.size(if (wide) 84.dp else 68.dp), compact = true)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AppText(device.profile.shortLabel, role = AppTextRole.Title)
                            AppText("控制连接", role = AppTextRole.Footnote)
                            AppText(
                                state.connection.phaseOrNull()?.displayText() ?: "未连接",
                                role = AppTextRole.Summary,
                                color = if (state.isReady(device)) {
                                    appColor(AppColorRole.Primary)
                                } else {
                                    appColor(AppColorRole.Summary)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BatteryStrip(
                        device = device,
                        initializing = state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING,
                    )
                }
            }
            val controls: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (device.capabilities.noiseControl != null) {
                        NoiseControlCard(state, device, onSet)
                    }
                    if (DeviceSetting.GAME_MODE in device.capabilities.writableSettings) {
                        AppDashboardCard(compact = true) {
                            SettingToggleChoices(
                                "游戏模式", DeviceField.GAME_MODE, DeviceSetting.GAME_MODE,
                                device, state.isReady(device) && !state.hasPendingSetting(device), onSet,
                                icon = RoseIcon.GAME,
                            )
                        }
                    }
                    val hasEqualizer = device.capabilities.equalizer != null
                    val hasTouch = device.capabilities.touchControl != null ||
                        DeviceSetting.TOUCH_ENABLED in device.capabilities.writableSettings ||
                        DeviceSetting.EAR_DETECTION in device.capabilities.writableSettings
                    val hasAudio = device.capabilities.writableSettings.any {
                        it in setOf(
                            DeviceSetting.AUDIO_MODE,
                            DeviceSetting.MULTIPOINT,
                            DeviceSetting.PROMPT_VOLUME,
                            DeviceSetting.GAIN,
                            DeviceSetting.HEARING_PROTECTION,
                        )
                    }
                    AppGroup(title = "功能") {
                        if (hasEqualizer) {
                            AppArrowRow(
                                title = "均衡器",
                                value = (device.knownValue(DeviceField.EQ) as? FieldValue.Text)
                                    ?.value?.let { equalizerLabel(it, device.profile) },
                                icon = RoseIcon.EQUALIZER,
                                onClick = { onNavigate(AppPage.EQUALIZER) },
                            )
                        }
                        if (hasTouch) {
                            AppArrowRow(
                                title = "触控操作",
                                icon = RoseIcon.TOUCH,
                                onClick = { onNavigate(AppPage.TOUCH) },
                            )
                        }
                        if (hasAudio) {
                            AppArrowRow(
                                title = "音频与设备",
                                value = (device.knownValue(DeviceField.AUDIO_MODE) as? FieldValue.Text)
                                    ?.value?.let(::semanticLabel),
                                icon = RoseIcon.AUDIO,
                                onClick = { onNavigate(AppPage.AUDIO) },
                            )
                        }
                        NoiseCycleRow(state, device, onSet)
                        AppArrowRow(
                            title = "设备详情",
                            icon = RoseIcon.INFO,
                            onClick = { onNavigate(AppPage.DETAILS) },
                        )
                    }
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(0.9f)) { deviceCard() }
                    Column(Modifier.weight(1.1f)) { controls() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    deviceCard()
                    controls()
                }
            }
        }
    }
}

@Composable
private fun UnconnectedHeadphonesDashboard(
    state: ControlState,
    onNavigate: (AppPage) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
    onStopScan: () -> Unit,
) {
    if (state.platform.transportUnavailableForHome()) {
        val transport = state.platform.transport as TransportAvailability.Unavailable
        AppGroup {
            AppArrowRow(
                title = "查看连接与运行状态",
                summary = transport.reason,
                icon = RoseIcon.BLUETOOTH,
                onClick = { onNavigate(AppPage.DIAGNOSTICS) },
            )
        }
        if (state.scan == ScanState.Scanning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppBusyIndicator()
                AppText("正在寻找耳机…", role = AppTextRole.Summary)
            }
            AppButton("停止扫描", onStopScan, kind = AppButtonKind.Primary)
        }
        return
    }
    val visibleError = state.lastError
    visibleError?.let { error ->
        AppDashboardCard {
            AppText(error.message, color = appColor(AppColorRole.Error))
            AppButton("查看详情", { onNavigate(AppPage.DIAGNOSTICS) }, kind = AppButtonKind.Secondary)
        }
    }


    val bondedRose = state.bondedDevices.filter { it.profile != null }
    if (bondedRose.isEmpty()) {
        AppDashboardCard {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EarbudsArtwork(compact = false)
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText("添加你的 ROSE 耳机", role = AppTextRole.Title)
                    AppText("请打开耳机盒盖并靠近手机，首次使用需配对连接", role = AppTextRole.Summary)
                }
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "搜索并添加耳机",
                        onClick = { onNavigate(AppPage.ADD_DEVICE) },
                        kind = AppButtonKind.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppButton(
                        text = "打开系统蓝牙设置",
                        onClick = onOpenSystemSettings,
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        return
    }

    var selectedDeviceId by remember(bondedRose) {
        mutableStateOf(bondedRose.first().deviceId)
    }
    val primaryDevice = bondedRose.firstOrNull { it.deviceId == selectedDeviceId } ?: bondedRose.first()
    val otherBonded = bondedRose.filter { it.deviceId != primaryDevice.deviceId }
    val connecting = state.connection as? ConnectionState.Connecting
    val isConnecting = connecting?.deviceId == primaryDevice.deviceId

    AppDashboardCard {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EarbudsArtwork(compact = false)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText(
                        primaryDevice.name?.takeIf(String::isNotBlank) ?: primaryDevice.profile!!.displayName,
                        role = AppTextRole.Title,
                    )
                    primaryDevice.profile?.let {
                        AppText(
                            it.configurationLabel,
                            role = AppTextRole.Footnote,
                            color = appColor(AppColorRole.Primary),
                        )
                    }
                }
                if (isConnecting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppBusyIndicator()
                        AppText("正在连接…", role = AppTextRole.Summary, color = appColor(AppColorRole.Primary))
                    }
                } else if (visibleError != null) {
                    AppText("连接未成功 · 请确认耳机已开盖并在附近", role = AppTextRole.Summary, color = appColor(AppColorRole.Error))
                } else {
                    AppText("未连接 · 耳机开盖后将自动连接", role = AppTextRole.Summary)
                }
            }
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isConnecting) {
                    AppButton(
                        text = "取消连接",
                        onClick = onDisconnect,
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    AppButton(
                        text = if (visibleError != null) "重试连接" else "连接",
                        onClick = { onConnect(primaryDevice.deviceId) },
                        kind = AppButtonKind.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (visibleError != null) {
                    AppButton(
                        text = "无法连接？打开系统蓝牙设置",
                        onClick = onOpenSystemSettings,
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (otherBonded.isNotEmpty()) {
        AppGroup(title = "其他已配对设备") {
            otherBonded.forEach { paired ->
                AppDashboardCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            AppIcon(RoseIcon.HEADPHONES)
                            Column {
                                AppText(paired.name?.takeIf(String::isNotBlank) ?: paired.profile!!.displayName)
                                paired.profile?.let {
                                    AppText(it.configurationLabel, role = AppTextRole.Summary)
                                }
                            }
                        }
                        AppButton(
                            text = "切换",
                            onClick = {
                                selectedDeviceId = paired.deviceId
                                onConnect(paired.deviceId)
                            },
                            kind = AppButtonKind.Secondary,
                        )
                    }
                }
            }
        }
    }

    AppGroup {
        AppArrowRow(
            title = "配对新耳机",
            summary = "搜索并连接附近的 ROSE 耳机",
            icon = RoseIcon.PLUS,
            onClick = { onNavigate(AppPage.ADD_DEVICE) },
        )
    }
}
@Composable
private fun BatteryStrip(device: DeviceState, initializing: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            "左耳" to DeviceField.LEFT_BATTERY,
            "右耳" to DeviceField.RIGHT_BATTERY,
            "充电盒" to DeviceField.CASE_BATTERY,
        ).forEach { (label, field) ->
            val percent = (device.knownValue(field) as? FieldValue.Number)
                ?.value?.takeIf { it in 0..100 }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(label, role = AppTextRole.Footnote)
                AppText(
                    readingText(field, device.fields[field], initializing, device.fieldErrors[field], device.profile),
                    role = AppTextRole.Body,
                    color = if (percent != null && percent <= 20) appColor(AppColorRole.Error) else null,
                )
                if (field != DeviceField.CASE_BATTERY || percent != null) {
                    AppProgress(percent?.div(100f))
                }
            }
        }
    }
}



@Composable
internal fun ConnectionDetailsCard(state: ControlState, onDisconnect: () -> Unit) {
    val connectionReason = state.transportSelection?.let {
        when (it.reason) {
            TransportSelectionReason.MODEL_BLE_ONLY -> "此型号使用 BLE 控制"
            TransportSelectionReason.FORCED_BLE -> "本次连接使用强制 BLE 偏好"
            TransportSelectionReason.SPP_UNAVAILABLE -> "当前平台未提供蓝牙串口控制"
            TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE -> "尚无可用的蓝牙串口地址"
            TransportSelectionReason.AUDIO_PROFILE_UNKNOWN -> "无法确认音频连接类型，使用 BLE"
            TransportSelectionReason.LE_AUDIO -> "已检测到 LE Audio 音频连接，使用 BLE"
            TransportSelectionReason.CLASSIC_AUDIO -> "已确认传统蓝牙音频连接，可使用串口控制"
        }
    }
    val recoveryInfo = state.connectionRecovery?.let { recovery ->
        val reason = when (recovery.reason) {
            TransportRecoveryReason.SERVICE_DISCOVERY -> "蓝牙服务发现暂不可用"
            TransportRecoveryReason.SERVICES_UNAVAILABLE -> "耳机控制服务尚未就绪"
            TransportRecoveryReason.SUBSCRIPTION -> "设备状态订阅未完成"
            TransportRecoveryReason.WRITE_FAILED -> "首次状态读取发送未完成"
            TransportRecoveryReason.NO_INITIAL_RESPONSE -> "首次读取尚未收到可用状态"
            TransportRecoveryReason.SERVICES_CHANGED -> "耳机的蓝牙服务已变化"
        }
        "本次已尝试恢复 ${recovery.attempt} 次；$reason"
    }
    val footnote = listOfNotNull(
        connectionReason,
        recoveryInfo,
        state.lastError?.message,
    ).joinToString("\n").ifBlank { null }

    AppGroup(title = "当前连接", footnote = footnote) {
        AppInfoRow("设备", state.activeDevice()?.profile?.displayName ?: "未连接")
        AppInfoRow("状态", state.connection.phaseOrNull()?.displayText() ?: "未连接耳机")
        AppInfoRow("控制连接", state.controlTransportLabel())
        if (state.connection != ConnectionState.Disconnected) {
            AppButton(
                text = if (state.connection is ConnectionState.Connecting) "取消连接" else "断开控制连接",
                onClick = onDisconnect,
                kind = AppButtonKind.Secondary,
            )
        }
    }
}

@Composable
internal fun DeviceReadings(device: DeviceState, initializing: Boolean) {
    var showDetails by remember(device.sessionId) { mutableStateOf(false) }

    AppGroup(title = "设备状态") {
        AppInfoRow(
            "读取状态",
            if (initializing) "正在读取设备状态…" else "按设备实际返回的状态显示。",
        )
        device.capabilities.readableFields.sortedBy { it.ordinal }.forEach { field ->
            AppInfoRow(
                field.displayName,
                readingText(field, device.fields[field], initializing, device.fieldErrors[field], device.profile),
            )
        }
        if (device.capabilities.writableSettings.isEmpty()) {
            AppInfoRow("控制设置", "当前提供状态读取，控制设置尚未开放。")
        }
        if (device.warnings.isNotEmpty()) {
            AppInfoRow("读取提示", device.warnings.distinct().joinToString("\n"))
        }
        AppArrowRow(
            title = if (showDetails) "收起原始记录" else "查看原始记录",
            icon = RoseIcon.LOG,
            onClick = { showDetails = !showDetails },
        )
        if (showDetails) {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText("会话 ${device.sessionId} · ${device.deviceId.value}", role = AppTextRole.Footnote)
                    device.fields.forEach { (field, reading) ->
                        if (reading.rawHex.isNotEmpty()) {
                            AppText(
                                "${field.displayName}：${reading.rawHex}",
                                role = AppTextRole.Footnote,
                            )
                        }
                    }
                    if (device.rawNotifications.isNotEmpty()) {
                        AppText("最近收到的数据", role = AppTextRole.Summary)
                        device.rawNotifications.forEach {
                            AppText(it, role = AppTextRole.Footnote)
                        }
                    }
                    if (device.unknownNotifications.isNotEmpty()) {
                        AppText("未解释的数据", role = AppTextRole.Summary)
                        device.unknownNotifications.forEach {
                            AppText(it, role = AppTextRole.Footnote)
                        }
                    }
                }
            }
        }
    }
}

internal fun readingText(
    field: DeviceField,
    reading: FieldReading?,
    initializing: Boolean,
    error: ControlError?,
    profile: ModelProfile,
): String = when (reading) {
    null, FieldReading.Loading -> when {
        error?.code == ControlErrorCode.TIMEOUT -> "读取超时"
        error != null -> "读取失败：${error.message}"
        initializing -> "读取中…"
        else -> "尚未读取"
    }
    is FieldReading.NotProvided -> "设备未提供"
    is FieldReading.Unknown -> "未知"
    is FieldReading.Known -> displayFieldValue(field, reading.value, profile)
}

internal fun ConnectionState.phaseOrNull(): ConnectionPhase? = when (this) {
    ConnectionState.Disconnected -> null
    is ConnectionState.Connecting -> phase
    is ConnectionState.Connected -> phase
}

internal fun ConnectionState.matches(device: DeviceState): Boolean = when (this) {
    ConnectionState.Disconnected -> false
    is ConnectionState.Connecting -> deviceId == device.deviceId && sessionId == device.sessionId
    is ConnectionState.Connected -> deviceId == device.deviceId && sessionId == device.sessionId
}

internal fun ConnectionPhase.displayText(): String = when (this) {
    ConnectionPhase.CONNECTING -> "正在建立蓝牙连接…"
    ConnectionPhase.DISCOVERING -> "正在发现耳机服务…"
    ConnectionPhase.SUBSCRIBING -> "正在订阅状态通知…"
    ConnectionPhase.INITIALIZING -> "正在初始化并读取状态…"
    ConnectionPhase.READY -> "已连接 · 控制会话已就绪"
}
