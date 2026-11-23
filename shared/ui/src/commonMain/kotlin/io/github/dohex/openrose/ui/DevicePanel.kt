@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.*
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.settingsFor

internal fun ControlState.activeDevice(): DeviceState? {
    val targetId = connectionTarget?.deviceId
    return device?.takeIf { connection.matches(it) && (targetId == null || targetId == it.deviceId) }
}

internal fun ControlState.controlTransportLabel(): String? = when (transportSelection?.endpoint) {
    is TransportEndpoint.BleGatt -> "BLE"
    is TransportEndpoint.SppRfcomm -> "蓝牙串口（SPP）"
    null -> if (connection == ConnectionState.Disconnected) null else "确定中"
}

@Composable
internal fun HeadphonesPage(
    model: OpenRoseModel,
    platformUi: PlatformUi,
    onNavigate: (AppPage) -> Unit,
    primaryDeviceSelection: PrimaryDeviceSelection = remember { PrimaryDeviceSelection() },
) {
    val state by model.state.collectAsState()
    val device = state.activeDevice()
    val cards = rememberHeadsetCards(state, primaryDeviceSelection, platformUi.headsetCirculation)
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoticeBanner(model)
        if (device == null) {
            UnconnectedHeadphonesDashboard(state, model, platformUi, cards, primaryDeviceSelection, onNavigate)
            return@Column
        }

        val onSet = model.settingsFor(device.sessionId)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 680.dp
            val deviceCard: @Composable () -> Unit = {
                ConnectedHeadsetCard(state, device, cards.local, platformUi.desktopSound, wide)
            }
            val controls: @Composable () -> Unit = {
                HeadsetControls(state, device, showLocalNoise = cards.local.remote == null, onSet, onNavigate)
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
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
        cards.others.forEach { AdditionalHeadsetCard(it, model::connect, platformUi.desktopSound) }
    }
}

@Composable
private fun ConnectedHeadsetCard(
    state: ControlState,
    device: DeviceState,
    source: HeadsetCardSource,
    sound: DesktopSoundActions?,
    wide: Boolean,
) {
    AppDashboardCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceArtwork(device.profile, Modifier.size(if (wide) 84.dp else 68.dp), compact = true)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(device.profile.shortLabel, role = AppTextRole.Title)
                source.sourceLabel?.let { AppText(it, role = AppTextRole.Summary) }
                AppText(
                    if (source.sourceLabel != null && state.isReady(device)) {
                        "耳机控制已连接"
                    } else {
                        state.connection.phaseOrNull()?.displayText() ?: "未连接"
                    },
                    role = AppTextRole.Summary,
                    color = if (state.isReady(device)) {
                        appColor(AppColorRole.Primary)
                    } else {
                        appColor(AppColorRole.Summary)
                    },
                )
            }
        }
        HeadsetTransferAction(source)
        val remote = source.remote
        if (remote != null) {
            RemoteHeadsetReadings(remote, enabled = !source.transferBusy)
        } else {
            BatteryStrip(
                device = device,
                initializing = state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING,
            )
        }
        sound?.let { DesktopOutputRow(it, source) }
    }
}

@Composable
private fun HeadsetControls(
    state: ControlState,
    device: DeviceState,
    showLocalNoise: Boolean,
    onSet: (HeadsetCommand) -> Unit,
    onNavigate: (AppPage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showLocalNoise) {
            NoiseControlCard(state, device, onSet = onSet)
        }
        if (DeviceSetting.GAME_MODE in device.capabilities.writableSettings) {
            AppDashboardCard(compact = true) {
                SettingToggleChoices(
                    "游戏模式",
                    DeviceField.GAME_MODE,
                    HeadsetCommand::GameMode,
                    device,
                    state.canWrite(device, DeviceSetting.GAME_MODE),
                    onSet,
                    icon = RoseIcon.GAME,
                    pendingToggle = state.pendingToggle(device, DeviceSetting.GAME_MODE),
                )
            }
        }
        val hasTouch = device.capabilities.touchControl != null || device.capabilities.tapControl != null ||
            device.capabilities.writableNoiseCycle != null ||
            DeviceSetting.TOUCH_ENABLED in device.capabilities.writableSettings ||
            DeviceSetting.EAR_DETECTION in device.capabilities.writableSettings
        val hasAudio = device.capabilities.writableSettings.any { it in audioPageSettings }
        AppGroup(title = "功能") {
            EqualizerEntryRow(state, device, onSet) { onNavigate(AppPage.EQUALIZER) }
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
                    value = device.confirmedText(DeviceField.AUDIO_MODE)?.let(::semanticLabel),
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
    }
}

@Composable
private fun UnconnectedHeadphonesDashboard(
    state: ControlState,
    model: OpenRoseModel,
    platformUi: PlatformUi,
    cards: HeadsetCards,
    selection: PrimaryDeviceSelection,
    onNavigate: (AppPage) -> Unit,
) {
    val platform = state.platform
    val blockReason = platform.connectionBlockReason
    if (state.connectionTarget == null && !platform.bluetoothPermissionRequired &&
        platform.transport is TransportAvailability.Unavailable
    ) {
        AppGroup {
            AppArrowRow(
                title = "诊断与记录",
                summary = blockReason,
                icon = RoseIcon.BLUETOOTH,
                onClick = { onNavigate(AppPage.DIAGNOSTICS) },
            )
        }
        if (state.scan == ScanState.Scanning) {
            AppDashboardCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppBusyIndicator()
                    AppText("正在寻找耳机…", role = AppTextRole.Summary)
                }
                AppButton("停止扫描", model::stopScan, kind = AppButtonKind.Primary)
            }
        }
        if (cards.local.remote != null) AdditionalHeadsetCard(cards.local)
        cards.others.forEach { AdditionalHeadsetCard(it, model::connect, platformUi.desktopSound) }
        return
    }

    val primaryDevice = cards.local.target
    if (primaryDevice == null && cards.showLocal) {
        AddFirstHeadsetCard(platform, state.lastError, onNavigate, platformUi.openBluetoothSettings) { onNavigate(AppPage.DIAGNOSTICS) }
        return
    }

    val bondedRose = state.bondedRoseDevices()
    DisposableEffect(selection, bondedRose) { onDispose { selection.clear() } }
    val connecting = state.connection as? ConnectionState.Connecting
    if (primaryDevice != null) {
        ReconnectHeadsetCard(state, primaryDevice, cards.local, connecting?.deviceId == primaryDevice.deviceId, model, platformUi) {
            onNavigate(AppPage.DIAGNOSTICS)
        }
    }

    cards.others.forEach { AdditionalHeadsetCard(it, model::connect, platformUi.desktopSound) }
    val shown = cards.others.mapNotNull { it.target?.deviceId }
    val otherBonded = bondedRose.filter { it.deviceId != primaryDevice?.deviceId && it.deviceId !in shown }
    OtherPairedDevices(otherBonded, blockReason == null && connecting == null) { id ->
        selection.select(state, id)
        model.connect(id)
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
private fun ReconnectHeadsetCard(
    state: ControlState,
    primaryDevice: DeviceCandidate,
    source: HeadsetCardSource,
    isConnecting: Boolean,
    model: OpenRoseModel,
    platformUi: PlatformUi,
    onDiagnostics: () -> Unit,
) {
    AppDashboardCard {
        if (source.sourceLabel != null) {
            HeadsetSourceContent(source)
            ReconnectStatus(state, primaryDevice, isConnecting, source)
        } else {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StyledTile(TileKind.ArtworkFrame, contentPadding = PaddingValues(12.dp), contentAlignment = Alignment.Center) {
                    DeviceArtwork(primaryDevice.profile)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(primaryDevice.cardName(), role = AppTextRole.Title)
                    primaryDevice.profile?.let {
                        AppText(it.configurationCaption, role = AppTextRole.Footnote, color = appColor(AppColorRole.Primary))
                    }
                }
                ReconnectStatus(state, primaryDevice, isConnecting)
                HeadsetTransferAction(source)
            }
        }
        platformUi.desktopSound?.let { DesktopOutputRow(it, source) }
        ReconnectActions(state, primaryDevice, source, isConnecting, model, platformUi, onDiagnostics)
    }
}

@Composable
internal fun BatteryStrip(device: DeviceState, initializing: Boolean, showUnknown: Boolean = false) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(if (isMaterial3) 8.dp else 12.dp),
    ) {
        device.profile.batteryReadings().forEach { (label, field) ->
            StyledTile(
                TileKind.OutlinedChip,
                Modifier.weight(1f).fillMaxHeight().testTag("battery-$field"),
                PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BatteryReading(label, field, device, initializing, showUnknown)
            }
        }
    }
}

@Composable
private fun BatteryReading(label: String, field: DeviceField, device: DeviceState, initializing: Boolean, showUnknown: Boolean) {
    val reading = device.reading(field)
    val percent = device.confirmedNumber(field)
    val low = percent != null && percent <= LOW_BATTERY_PERCENT
    val text = readingText(field, reading, initializing, device.diagnostics.fieldErrors[field], device.profile)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(label, role = AppTextRole.Footnote)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (reading is FieldReading.NotProvided) {
                AppText("–", Modifier.semantics { contentDescription = text })
            } else {
                AppText(
                    if (showUnknown && percent == null) "未知" else text,
                    color = if (low) appColor(AppColorRole.Error) else null,
                )
            }
            if (low) {
                AppIcon(RoseIcon.WARNING, Modifier.size(16.dp), contentDescription = "电量低", tint = appColor(AppColorRole.Error))
            }
        }
        AppProgress(percent?.div(100f))
    }
}

@Composable
internal fun DeviceDetails(state: ControlState, device: DeviceState, onDisconnect: () -> Unit, onDiagnostics: () -> Unit) {
    val initializing = state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING
    AppGroup {
        AppInfoRow("型号配置", "${device.profile.configurationLabel} · ${device.profile.family.displayName}")
        if (DeviceField.FIRMWARE in device.capabilities.readableFields) {
            AppInfoRow(
                "固件",
                readingText(
                    DeviceField.FIRMWARE,
                    device.reading(DeviceField.FIRMWARE),
                    initializing,
                    device.diagnostics.fieldErrors[DeviceField.FIRMWARE],
                    device.profile,
                ),
            )
        }
        state.controlTransportLabel()?.let { AppInfoRow("控制传输", it) }
    }
    AppGroup {
        AppArrowRow("诊断与记录", icon = RoseIcon.LOG, onClick = onDiagnostics)
    }
    val inset = if (LocalInterfaceStyle.current == InterfaceStyle.MIUIX) 12.dp else 16.dp
    AppButton(
        text = if (state.connection is ConnectionState.Connecting) "取消连接" else "断开控制连接",
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = inset),
        kind = AppButtonKind.Secondary,
    )
}

@Composable
internal fun ConnectionDetailsCard(state: ControlState) {
    val connectionReason = state.transportSelection?.reason?.description()
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
        AppInfoRow("设备", state.activeDevice()?.profile?.configurationLabel ?: "未连接")
        AppInfoRow("连接阶段", state.connection.phaseOrNull()?.displayText() ?: "未连接耳机")
        state.controlTransportLabel()?.let { AppInfoRow("控制传输", it) }
    }
}

@Composable
internal fun DeviceReadings(device: DeviceState, initializing: Boolean) {
    var showDetails by remember(device.sessionId) { mutableStateOf(false) }
    val footnote = listOfNotNull(
        if (initializing) "正在读取设备状态…" else "按设备实际返回的状态显示。",
        "当前提供状态读取，控制设置尚未开放。".takeIf { device.capabilities.writableSettings.isEmpty() },
    ).plus(device.diagnostics.warnings.distinct()).joinToString("\n")

    AppGroup(title = "设备读数", footnote = footnote) {
        device.capabilities.readableFields.sortedBy { it.ordinal }.forEach { field ->
            AppInfoRow(
                field.displayName,
                readingText(
                    field,
                    device.diagnostics.fields[field],
                    initializing,
                    device.diagnostics.fieldErrors[field],
                    device.profile,
                    if (field == DeviceField.TAP_KEYS) device.capabilities.tapControl else device.capabilities.touchControl,
                ),
            )
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
                    device.diagnostics.fields.forEach { (field, reading) ->
                        if (reading.rawHex.isNotEmpty()) {
                            AppText(
                                "${field.displayName}：${reading.rawHex}",
                                role = AppTextRole.Footnote,
                            )
                        }
                    }
                    if (device.diagnostics.rawNotifications.isNotEmpty()) {
                        AppText("最近收到的数据", role = AppTextRole.Summary)
                        device.diagnostics.rawNotifications.forEach {
                            AppText(it, role = AppTextRole.Footnote)
                        }
                    }
                    if (device.diagnostics.unknownNotifications.isNotEmpty()) {
                        AppText("未解释的数据", role = AppTextRole.Summary)
                        device.diagnostics.unknownNotifications.forEach {
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
    touch: TouchControlCapabilities? = null,
): String = when (reading) {
    null, FieldReading.Loading -> when {
        error?.code == ControlErrorCode.TIMEOUT -> "读取超时"
        error != null -> "读取失败：${error.message}"
        initializing -> "读取中…"
        else -> "尚未读取"
    }

    is FieldReading.NotProvided -> "设备未提供"

    is FieldReading.Unknown -> "未知"

    is FieldReading.Known -> displayFieldValue(field, reading.value, profile, touch)
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
    ConnectionPhase.READY -> "已连接"
}

private fun TransportSelectionReason.description(): String = when (this) {
    TransportSelectionReason.MODEL_BLE_ONLY -> "此型号使用 BLE 控制"
    TransportSelectionReason.FORCED_BLE -> "本次连接使用强制 BLE 偏好"
    TransportSelectionReason.SPP_UNAVAILABLE -> "当前平台未提供蓝牙串口控制"
    TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE -> "尚无可用的蓝牙串口地址"
    TransportSelectionReason.AUDIO_PROFILE_UNKNOWN -> "无法确认音频连接类型，使用 BLE"
    TransportSelectionReason.LE_AUDIO -> "已检测到 LE Audio 音频连接，使用 BLE"
    TransportSelectionReason.CLASSIC_AUDIO -> "已确认传统蓝牙音频连接，可使用串口控制"
}

@Composable
private fun AddFirstHeadsetCard(
    platform: PlatformState,
    error: ControlError?,
    onNavigate: (AppPage) -> Unit,
    onOpenSystemSettings: (() -> Unit)?,
    onDiagnostics: () -> Unit,
) {
    AppDashboardCard {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StyledTile(TileKind.ArtworkFrame, contentPadding = PaddingValues(12.dp), contentAlignment = Alignment.Center) {
                EarbudsArtwork(compact = false)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText("添加你的 ROSE 耳机", role = AppTextRole.Title, textAlign = TextAlign.Center)
                val hint = if (platform.transport == TransportAvailability.Disabled) {
                    "系统蓝牙已关闭"
                } else {
                    platform.hostPlatform.firstConnectionHint()
                }
                AppText(hint, role = AppTextRole.Summary, textAlign = TextAlign.Center)
                error?.let {
                    AppText(
                        it.message,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        role = AppTextRole.Summary,
                        color = appColor(AppColorRole.Error),
                        textAlign = TextAlign.Center,
                    )
                }
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
                onOpenSystemSettings?.let { openSettings ->
                    AppButton(
                        text = "打开系统蓝牙设置",
                        onClick = openSettings,
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) {
                    AppButton(
                        text = "查看诊断",
                        onClick = onDiagnostics,
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReconnectActions(
    state: ControlState,
    primaryDevice: DeviceCandidate,
    source: HeadsetCardSource,
    isConnecting: Boolean,
    model: OpenRoseModel,
    platformUi: PlatformUi,
    onDiagnostics: () -> Unit,
) {
    val platform = state.platform
    val onOpenSystemSettings = platformUi.openBluetoothSettings
    val blockReason = platform.connectionBlockReason
    val visibleError = state.lastError
    if (source.transferBusy || (source.offersTransfer && visibleError == null)) return
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isConnecting) {
            AppButton(
                text = "取消连接",
                onClick = model::disconnect,
                kind = AppButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (blockReason != null) {
            BluetoothConnectionRemedy(platform, platformUi.requestBluetoothPermission, onOpenSystemSettings)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!source.offersTransfer) {
                    AppButton(
                        text = source.reconnectLabel(if (visibleError != null) "重试连接" else "连接"),
                        onClick = { model.connect(primaryDevice.deviceId) },
                        kind = AppButtonKind.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (visibleError != null) {
                    AppButton(
                        text = "查看诊断",
                        onClick = onDiagnostics,
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (visibleError != null && blockReason == null && onOpenSystemSettings != null) {
            AppButton(
                text = "无法连接？打开系统蓝牙设置",
                onClick = onOpenSystemSettings,
                kind = AppButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BluetoothConnectionRemedy(platform: PlatformState, requestPermission: (() -> Unit)?, openSettings: (() -> Unit)?) {
    val (label, action) = if (platform.bluetoothPermissionRequired) "授予蓝牙权限" to requestPermission else "打开系统蓝牙设置" to openSettings
    action?.let { AppButton(label, it, kind = AppButtonKind.Primary, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun OtherPairedDevices(devices: List<DeviceCandidate>, enabled: Boolean, onSelect: (DeviceId) -> Unit) {
    if (devices.isNotEmpty()) {
        AppGroup(title = "其他已配对设备") {
            val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
            devices.forEach { paired ->
                val name = paired.cardName()
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isMaterial3) {
                            Modifier
                                .clip(ListItemDefaults.shapes().shape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                Row(
                    rowModifier,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        StyledTile(TileKind.Chip, contentPadding = PaddingValues(8.dp), contentAlignment = Alignment.Center) {
                            AppIcon(RoseIcon.HEADPHONES)
                        }
                        Column {
                            AppText(name)
                            paired.profile?.let {
                                AppText(it.configurationCaption, role = AppTextRole.Summary)
                            }
                        }
                    }
                    AppButton(
                        text = "切换",
                        enabled = enabled,
                        onClick = {
                            onSelect(paired.deviceId)
                        },
                        kind = AppButtonKind.Secondary,
                        modifier = Modifier.semantics { contentDescription = "切换到 $name" },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReconnectStatus(state: ControlState, primaryDevice: DeviceCandidate, isConnecting: Boolean, source: HeadsetCardSource? = null) {
    val blockReason = state.platform.connectionBlockReason
    val visibleError = state.lastError?.takeIf { blockReason == null }
    val hint = when {
        blockReason != null -> blockReason
        source?.hasLocalAudio == true -> audioWithoutControlStatus(false)
        source == null -> primaryDevice.profile.reconnectHint(failed = false)
        else -> null
    }
    if (!isConnecting && visibleError == null && hint == null) return
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    StyledTile(
        TileKind.Chip,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isConnecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppBusyIndicator()
                val progress = if (source?.hasLocalAudio == true) audioWithoutControlStatus(true) else "正在连接…"
                AppText(progress, announce, role = AppTextRole.Summary, color = appColor(AppColorRole.Primary))
            }

            visibleError != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AppText(
                    visibleError.message,
                    announce,
                    role = AppTextRole.Summary,
                    color = appColor(AppColorRole.Error),
                    textAlign = TextAlign.Center,
                )
                if (source == null) {
                    AppText(primaryDevice.profile.reconnectHint(failed = true), role = AppTextRole.Summary, textAlign = TextAlign.Center)
                }
            }

            hint != null -> AppText(hint, announce, role = AppTextRole.Summary)
        }
    }
}

private const val LOW_BATTERY_PERCENT = 20

private enum class TileKind { ArtworkFrame, Chip, OutlinedChip }

@Composable
private fun StyledTile(
    kind: TileKind,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalInterfaceStyle.current != InterfaceStyle.MATERIAL3) {
        Box(modifier, contentAlignment, content = content)
        return
    }
    val framed = kind == TileKind.ArtworkFrame
    val shape = if (framed) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    val container = if (framed) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer
    val outline = if (kind == TileKind.Chip) {
        Modifier
    } else {
        Modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            shape,
        )
    }
    Box(
        modifier.clip(shape).background(container).then(outline).padding(contentPadding),
        contentAlignment,
        content = content,
    )
}
