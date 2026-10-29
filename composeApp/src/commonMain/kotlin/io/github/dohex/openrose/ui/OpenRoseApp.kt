package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId

internal enum class AppPage(val title: String, val icon: RoseIcon) {
    HEADPHONES("我的耳机", RoseIcon.HEADPHONES),
    SYSTEM("系统集成", RoseIcon.SYSTEM),
    SETTINGS("设置", RoseIcon.SETTINGS),
    ADD_DEVICE("添加耳机", RoseIcon.PLUS),
    NOISE("降噪控制", RoseIcon.NOISE),
    EQUALIZER("均衡器", RoseIcon.EQUALIZER),
    TOUCH("触控操作", RoseIcon.TOUCH),
    AUDIO("音频与设备", RoseIcon.AUDIO),
    DETAILS("设备详情", RoseIcon.INFO),
    DIAGNOSTICS("诊断与记录", RoseIcon.LOG),
}

internal val mainPages = listOf(AppPage.HEADPHONES, AppPage.SYSTEM, AppPage.SETTINGS)

/** Navigation, scroll positions and drafts survive replacement of either component style.
 * The host remains the sole owner of the control session and transport. */
@Composable
fun OpenRoseApp(
    state: ControlState,
    onSettingsChange: (UserSettings) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
    onSet: (DeviceSetting, FieldValue) -> Unit,
    modifier: Modifier = Modifier,
    homeNavigationRequest: Long = 0L,
) {
    val dark = when (state.settings.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    var backStack by remember { mutableStateOf(listOf(AppPage.HEADPHONES)) }
    val page = backStack.last()
    val scrollStates = remember { AppPage.entries.associateWith { ScrollState(0) } }
    var selectedTouchSlot by remember(state.device?.sessionId) { mutableStateOf<TouchSlot?>(null) }
    var customEqualizerDraft by remember(state.device?.sessionId) { mutableStateOf<List<Int>?>(null) }
    var customEqualizerView by remember(state.device?.sessionId) { mutableStateOf(false) }
    var headphoneMenuExpanded by remember { mutableStateOf(false) }
    val rootFocusRequester = remember { FocusRequester() }
    val choiceDialog = remember(state.device?.sessionId) { mutableStateOf<String?>(null) }
    var selectedTouchSide by remember(state.device?.sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(homeNavigationRequest) {
        if (homeNavigationRequest != 0L) {
            backStack = listOf(AppPage.HEADPHONES)
            headphoneMenuExpanded = false
            choiceDialog.value = null
            selectedTouchSlot = null
        }
    }
    fun navigate(destination: AppPage) {
        if (destination != backStack.last()) {
            headphoneMenuExpanded = false
            choiceDialog.value = null
            selectedTouchSlot = null
            backStack = backStack + destination
        }
    }
    fun back() {
        when {
            headphoneMenuExpanded -> headphoneMenuExpanded = false
            selectedTouchSlot != null -> selectedTouchSlot = null
            choiceDialog.value != null -> choiceDialog.value = null
            backStack.size > 1 -> backStack = backStack.dropLast(1)
        }
    }
    PlatformBackHandler(
        backStack.size > 1 || choiceDialog.value != null || selectedTouchSlot != null || headphoneMenuExpanded,
        ::back,
    )
    LaunchedEffect(state.connection, page) {
        if (page != AppPage.HEADPHONES || state.activeDevice() == null) {
            headphoneMenuExpanded = false
        }
        val connection = state.connection as? ConnectionState.Connected
        if (page == AppPage.ADD_DEVICE && connection?.phase == ConnectionPhase.READY) {
            backStack = listOf(AppPage.HEADPHONES)
        }
    }
    CompositionLocalProvider(LocalChoiceDialog provides choiceDialog) {
        ProvideInterfaceStyle(state.settings.interfaceStyle, dark) {
            LaunchedEffect(page, choiceDialog.value, selectedTouchSlot, headphoneMenuExpanded) {
                if (choiceDialog.value == null && selectedTouchSlot == null && !headphoneMenuExpanded) {
                    rootFocusRequester.requestFocus()
                }
            }
            BoxWithConstraints(modifier.fillMaxSize().focusRequester(rootFocusRequester).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Escape &&
                    (backStack.size > 1 || choiceDialog.value != null || selectedTouchSlot != null || headphoneMenuExpanded)
                ) {
                    back(); true
                } else false
            }.focusable()) {
                val wide = maxWidth >= 900.dp
                val onNavigate: (AppPage) -> Unit = { destination ->
                    if (destination in mainPages) {
                        headphoneMenuExpanded = false
                        choiceDialog.value = null
                        selectedTouchSlot = null
                        backStack = listOf(destination)
                    } else navigate(destination)
                }
                val pageContent: @Composable () -> Unit = {
                    val device = state.activeDevice()
                    when (page) {
                        AppPage.HEADPHONES -> HeadphonesPage(
                            state,
                            onNavigate,
                            onRequestPermission,
                            onOpenSystemSettings,
                            onStartScan,
                            onStopScan,
                            onConnect,
                            onDisconnect,
                            onSet,
                        )
                        AppPage.ADD_DEVICE -> AddDevicePage(state, onRequestPermission, onOpenSystemSettings,
                            onStartScan, onStopScan, onConnect, onDisconnect) {
                            navigate(AppPage.DIAGNOSTICS)
                        }
                        AppPage.SYSTEM -> SystemIntegrationPage(
                            state = state,
                            onRequestPermission = onRequestPermission,
                            onOpenSystemSettings = onOpenSystemSettings,
                            onDiagnostics = { navigate(AppPage.DIAGNOSTICS) },
                        )
                        AppPage.SETTINGS -> PreferencesPage(state, onSettingsChange)
                        AppPage.DIAGNOSTICS -> {
                            PlatformDiagnosticsCard(state.platform, onRequestPermission, onOpenSystemSettings)
                            ConnectionDetailsCard(state, onDisconnect)
                            SettingOperationsCard(state)
                            device?.let { DeviceReadings(it, state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING) }
                        }
                        else -> if (device != null) {
                            val controlPage = page in listOf(AppPage.NOISE, AppPage.EQUALIZER, AppPage.TOUCH, AppPage.AUDIO)
                            if (controlPage) {
                                SettingPageBanner(state, device)
                            }
                            when (page) {
                                AppPage.NOISE -> NoiseControlCard(state, device, onSet)
                                AppPage.EQUALIZER -> {
                                    val hasCustom = device.capabilities.equalizer?.custom != null &&
                                        DeviceSetting.CUSTOM_EQ in device.capabilities.writableSettings
                                    if (hasCustom) AppViewTabs(listOf("预设", "自定义"), if (customEqualizerView) 1 else 0) { customEqualizerView = it == 1 }
                                    if (hasCustom && customEqualizerView) CustomEqualizerCard(state, device, customEqualizerDraft,
                                        { customEqualizerDraft = it }, onSet)
                                    else EqualizerControlCard(state, device, onSet)
                                }
                                AppPage.TOUCH -> TouchControlCard(state, device, selectedTouchSlot,
                                    { selectedTouchSlot = it }, onSet, selectedTouchSide, { selectedTouchSide = it })
                                AppPage.AUDIO -> {
                                    DeviceControlCard(state, device, onSet)
                                    AppGroup {
                                        AppArrowRow("设备详情", icon = RoseIcon.INFO) { navigate(AppPage.DETAILS) }
                                        AppArrowRow("连接方式", value = state.controlTransportLabel(), icon = RoseIcon.BLUETOOTH) { navigate(AppPage.DIAGNOSTICS) }
                                    }
                                    AppButton("断开控制连接", onDisconnect, modifier = Modifier.padding(horizontal = 16.dp), kind = AppButtonKind.Secondary)
                                }
                                AppPage.DETAILS -> {
                                    ConnectionDetailsCard(state, onDisconnect)
                                    AppGroup(title = "设备标识") {
                                        AppInfoRow("型号配置", device.profile.configurationLabel)
                                    }
                                    DeviceReadings(device, state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING)
                                }
                                else -> Unit
                            }
                            if (controlPage) {
                                AppText("选择表示设备已确认的状态；提交后等待设备回报确认。", Modifier.padding(horizontal = 16.dp), AppTextRole.Footnote)
                            }
                        } else {
                            AppGroup {
                                AppInfoRow("耳机已断开", "连接耳机后，可查看和调整当前型号支持的功能。")
                                AppButton("添加耳机", { navigate(AppPage.ADD_DEVICE) })
                            }
                        }
                    }
                }
                val feedbackContent: @Composable () -> Unit = {
                    val device = state.activeDevice()
                    if ((page == AppPage.HEADPHONES && device != null) ||
                        page in listOf(AppPage.NOISE, AppPage.EQUALIZER, AppPage.TOUCH, AppPage.AUDIO)) {
                        key(page) {
                            SettingFeedback(state, device) { navigate(AppPage.DIAGNOSTICS) }
                        }
                    }
                }
                when (state.settings.interfaceStyle) {
                    InterfaceStyle.MIUIX -> MiuixAppShell(
                        backStack = backStack,
                        page = page,
                        wide = wide,
                        onNavigate = onNavigate,
                        onBack = ::back,
                        scrollState = scrollStates.getValue(page),
                        connectedHome = state.activeDevice() != null,
                        showAddDeviceAction = !state.platform.transportUnavailableForHome(),
                        menuExpanded = headphoneMenuExpanded,
                        onMenuExpandedChange = { headphoneMenuExpanded = it },
                        onDisconnect = onDisconnect,
                        pageContent = pageContent,
                        feedbackContent = feedbackContent,
                    )
                    InterfaceStyle.MATERIAL3 -> MaterialAppShell(
                        backStack = backStack,
                        page = page,
                        wide = wide,
                        onNavigate = onNavigate,
                        onBack = ::back,
                        scrollState = scrollStates.getValue(page),
                        connectedHome = state.activeDevice() != null,
                        showAddDeviceAction = !state.platform.transportUnavailableForHome(),
                        menuExpanded = headphoneMenuExpanded,
                        onMenuExpandedChange = { headphoneMenuExpanded = it },
                        onDisconnect = onDisconnect,
                        pageContent = pageContent,
                        feedbackContent = feedbackContent,
                    )
                }
            }
        }
    }
}

internal val AppPage.navigationLabel: String
    get() = when (this) {
        AppPage.HEADPHONES -> "耳机"
        AppPage.SYSTEM -> "系统"
        else -> title
    }
