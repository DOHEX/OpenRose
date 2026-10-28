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
            AppText("连接你的 ROSE 耳机", role = AppTextRole.Title)
            val reason = when (val transport = state.platform.transport) {
                TransportAvailability.Available ->
                    if (state.platform.permission in setOf(PermissionState.REQUIRED, PermissionState.DENIED)) {
                        "需要蓝牙权限"
                    } else {
                        "等待连接耳机"
                    }
                TransportAvailability.Disabled -> "系统蓝牙已关闭"
                is TransportAvailability.Unavailable -> transport.reason
            }
            NearbyDevicesPanel(
                state,
                onRequestPermission,
                onOpenSystemSettings,
                onStartScan,
                onStopScan,
                onConnect,
                onDisconnect,
                onDiagnostics = { onNavigate(AppPage.DIAGNOSTICS) },
            )
            AppGroup {
                AppArrowRow(
                    title = "连接与运行状态",
                    value = reason,
                    icon = RoseIcon.BLUETOOTH,
                    onClick = { onNavigate(AppPage.DIAGNOSTICS) },
                )
            }
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
                        NoiseQuickCard(state, device, onSet) { onNavigate(AppPage.NOISE) }
                    }
                    val hasEqualizer = device.capabilities.equalizer != null ||
                        DeviceSetting.GAME_MODE in device.capabilities.writableSettings
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
                        AppArrowRow(
                            title = "设备详情",
                            icon = RoseIcon.INFO,
                            onClick = { onNavigate(AppPage.DETAILS) },
                        )
                    }
                    SettingFeedback(state, device) { onNavigate(AppPage.DIAGNOSTICS) }
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
private fun NoiseQuickCard(
    state: ControlState,
    device: DeviceState,
    onSet: (DeviceSetting, FieldValue) -> Unit,
    onMore: () -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
    val confirmed = (device.knownValue(DeviceField.NOISE_MODE) as? FieldValue.Text)?.value
    val hasPendingSetting = state.hasPendingSetting(device)
    val enabled = state.isReady(device) && !hasPendingSetting
    val modeOptions = listOf("normal", "anc", "transparency")
        .filter { it in capabilities.modes }
        .map { mode ->
            AppOption(
                value = mode,
                label = when (mode) {
                    "normal" -> "标准"
                    "anc" -> "降噪"
                    else -> "通透"
                },
            )
        }

    AppDashboardCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("降噪模式", Modifier.weight(1f), role = AppTextRole.Title)
            AppText(
                if (hasPendingSetting) {
                    "等待设备确认"
                } else {
                    confirmed?.let { "已确认：${semanticLabel(it)}" } ?: "当前状态未知"
                },
                role = AppTextRole.Summary,
            )
        }
        AppSegmented(
            options = modeOptions,
            selected = confirmed,
            enabled = enabled && DeviceSetting.NOISE_MODE in device.capabilities.writableSettings,
            onSelect = { mode ->
                if (confirmed != mode) onSet(DeviceSetting.NOISE_MODE, FieldValue.Text(mode))
            },
        )
        val levelSetting = when (confirmed) {
            "anc" -> DeviceSetting.ANC_LEVEL
            "transparency" -> DeviceSetting.TRANSPARENCY_LEVEL
            else -> null
        }
        val level = levelSetting?.let(capabilities::levelCapabilities)
        if (
            levelSetting != null &&
            levelSetting in device.capabilities.writableSettings &&
            level?.isVisibleIn(confirmed) == true
        ) {
            val field = if (levelSetting == DeviceSetting.ANC_LEVEL) {
                DeviceField.ANC_LEVEL
            } else {
                DeviceField.TRANSPARENCY_LEVEL
            }
            val selected = (device.knownValue(field) as? FieldValue.Number)?.value
            AppSegmented(
                options = level.levels.sorted().map { value ->
                    AppOption(
                        value = value,
                        label = value.toString(),
                        contentDescription = "${field.displayName} $value 档",
                    )
                },
                selected = selected,
                enabled = enabled,
                onSelect = { value ->
                    if (selected != value) onSet(levelSetting, FieldValue.Number(value))
                },
            )
        }
        AppArrowRow(
            title = "更多降噪设置",
            icon = RoseIcon.NOISE,
            onClick = onMore,
        )
    }
}

@Composable
internal fun AddDevicePage(
    state: ControlState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    NearbyDevicesPanel(
        state,
        onRequestPermission,
        onOpenSystemSettings,
        onStartScan,
        onStopScan,
        onConnect,
        onDisconnect,
        onDiagnostics,
    )
}

@Composable
private fun NearbyDevicesPanel(
    state: ControlState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val canScan = state.platform.transport == TransportAvailability.Available &&
        state.platform.permission in setOf(PermissionState.GRANTED, PermissionState.NOT_REQUIRED)
    val connecting = state.connection as? ConnectionState.Connecting
    val connected = state.connection as? ConnectionState.Connected
    val idle = connecting == null && connected == null
    val failure = state.scan as? ScanState.Failed

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("让耳机保持开启，并靠近手机", role = AppTextRole.Footnote)

        if (!canScan) {
            AppGroup {
                when {
                    state.platform.permission in setOf(PermissionState.REQUIRED, PermissionState.DENIED) -> {
                        AppText("需要蓝牙权限", role = AppTextRole.Body)
                        AppButton(
                            text = "授予蓝牙权限",
                            onClick = onRequestPermission,
                            kind = AppButtonKind.Primary,
                        )
                    }
                    state.platform.transport == TransportAvailability.Disabled -> {
                        AppText("系统蓝牙已关闭", role = AppTextRole.Body)
                        AppButton(
                            text = "打开蓝牙设置",
                            onClick = onOpenSystemSettings,
                            kind = AppButtonKind.Primary,
                        )
                    }
                    else -> {
                        AppText(
                            (state.platform.transport as? TransportAvailability.Unavailable)?.reason
                                ?: "当前蓝牙传输不可用",
                            role = AppTextRole.Summary,
                        )
                    }
                }
            }
        }

        if (connecting != null || connected != null) {
            AppGroup {
                AppInfoRow(
                    "连接阶段",
                    connecting?.phase?.displayText() ?: connected!!.phase.displayText(),
                )
                AppButton(
                    text = if (connecting != null) "取消连接" else "断开连接",
                    onClick = onDisconnect,
                    kind = AppButtonKind.Secondary,
                )
            }
        }

        val candidates = state.candidates.sortedWith(
            compareByDescending<DeviceCandidate> { it.profile != null }
                .thenBy { it.deviceId.value },
        )
        AppGroup(title = "附近设备", titleTrailing = {
            if (state.scan == ScanState.Scanning) AppBusyIndicator()
        }, plain = candidates.isEmpty()) {
            if (candidates.isEmpty()) {
                AppText(
                    if (state.scan == ScanState.Scanning) {
                        "正在寻找耳机…"
                    } else {
                        "还没有发现耳机。请确认耳机已开启并在附近。"
                    },
                    role = AppTextRole.Summary,
                )
            } else {
                candidates.forEach { candidate ->
                    val summary = candidate.profile?.let { profile ->
                        buildList {
                            add("已识别")
                            add(profile.configurationLabel)
                            candidate.rssi?.let { add("$it dBm") }
                        }.joinToString(" · ")
                    } ?: "未知型号配置，无法连接"
                    AppCandidateRow(
                        title = candidate.name?.takeIf(String::isNotBlank) ?: "未命名蓝牙设备",
                        summary = summary,
                        onConnect = candidate.profile?.let {
                            { onConnect(candidate.deviceId) }
                        },
                        enabled = canScan && idle,
                    )
                }
            }
        }

        val visibleError = state.lastError ?: failure?.error
        visibleError?.let { error ->
            val scanFailure = failure?.error == error
            AppDashboardCard {
                AppText(
                    if (scanFailure) "扫描未完成：${error.message}" else error.message,
                    color = appColor(AppColorRole.Error),
                )
                AppButton("查看详情", onDiagnostics, kind = AppButtonKind.Secondary)
            }
        }

        AppButton(
            text = if (state.scan == ScanState.Scanning) {
                "停止扫描"
            } else if (failure != null) {
                "重新扫描"
            } else {
                "扫描耳机"
            },
            onClick = if (state.scan == ScanState.Scanning) onStopScan else onStartScan,
            enabled = if (state.scan == ScanState.Scanning) true else canScan && idle,
            kind = AppButtonKind.Primary,
        )
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
                text = if (state.connection is ConnectionState.Connecting) "取消连接" else "断开连接",
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
