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
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

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
    val navBackStack = rememberNavBackStack<Route>(Route.Main(AppPage.HEADPHONES))
    val currentRoute = (navBackStack.lastOrNull() as? Route) ?: Route.Main(AppPage.HEADPHONES)
    val page = when (currentRoute) {
        is Route.Main -> currentRoute.tab
        else -> currentRoute.toAppPage()
    }
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
            navBackStack.clear()
            navBackStack.add(Route.Main(AppPage.HEADPHONES))
            headphoneMenuExpanded = false
            choiceDialog.value = null
            selectedTouchSlot = null
        }
    }
    fun navigate(destination: AppPage) {
        headphoneMenuExpanded = false
        choiceDialog.value = null
        selectedTouchSlot = null
        if (destination in mainPages) {
            navBackStack.clear()
            navBackStack.add(Route.Main(destination))
        } else {
            val target = destination.toRoute()
            if (navBackStack.lastOrNull() != target) {
                navBackStack.add(target)
            }
        }
    }
    fun back() {
        when {
            headphoneMenuExpanded -> headphoneMenuExpanded = false
            selectedTouchSlot != null -> selectedTouchSlot = null
            choiceDialog.value != null -> choiceDialog.value = null
            navBackStack.size > 1 -> navBackStack.removeLastOrNull()
            currentRoute is Route.Main && currentRoute.tab != AppPage.HEADPHONES -> {
                navBackStack.clear()
                navBackStack.add(Route.Main(AppPage.HEADPHONES))
            }
        }
    }
    val canGoBack = headphoneMenuExpanded ||
        selectedTouchSlot != null ||
        choiceDialog.value != null ||
        navBackStack.size > 1 ||
        (currentRoute is Route.Main && currentRoute.tab != AppPage.HEADPHONES)
    PlatformBackHandler(
        canGoBack,
        ::back,
    )
    LaunchedEffect(state.connection, page) {
        if (page != AppPage.HEADPHONES || state.activeDevice() == null) {
            headphoneMenuExpanded = false
        }
        val connection = state.connection as? ConnectionState.Connected
        if (page == AppPage.ADD_DEVICE && connection?.phase == ConnectionPhase.READY) {
            navBackStack.clear()
            navBackStack.add(Route.Main(AppPage.HEADPHONES))
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
                if (it.type == KeyEventType.KeyUp && it.key == Key.Escape && canGoBack) {
                    back(); true
                } else false
            }.focusable()) {
                val wide = maxWidth >= 900.dp
                val onNavigate: (AppPage) -> Unit = ::navigate
                val pageContentFor: @Composable (AppPage) -> Unit = { targetPage ->
                    val device = state.activeDevice()
                    when (targetPage) {
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
                            val controlPage = targetPage in listOf(AppPage.NOISE, AppPage.EQUALIZER, AppPage.TOUCH, AppPage.AUDIO)
                            if (controlPage) {
                                SettingPageBanner(state, device)
                            }
                            when (targetPage) {
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
                val feedbackContentFor: @Composable (AppPage) -> Unit = { targetPage ->
                    val device = state.activeDevice()
                    if ((targetPage == AppPage.HEADPHONES && device != null) ||
                        targetPage in listOf(AppPage.NOISE, AppPage.EQUALIZER, AppPage.TOUCH, AppPage.AUDIO)) {
                        key(targetPage) {
                            SettingFeedback(state, device) { navigate(AppPage.DIAGNOSTICS) }
                        }
                    }
                }
                val renderShell: @Composable (AppPage, Boolean) -> Unit = { targetPage, isSecondary ->
                    val showBottomBar = !wide && !isSecondary && navBackStack.size == 1 && targetPage in mainPages
                    val shellContent: @Composable () -> Unit = { pageContentFor(targetPage) }
                    val shellFeedback: @Composable () -> Unit = { feedbackContentFor(targetPage) }
                    val isConnectedHome = state.activeDevice() != null
                    val showAdd = isConnectedHome && !state.platform.transportUnavailableForHome()
                    val scrollState = scrollStates.getValue(targetPage)

                    when (state.settings.interfaceStyle) {
                        InterfaceStyle.MIUIX -> MiuixAppShell(
                            page = targetPage,
                            wide = wide,
                            onNavigate = onNavigate,
                            onBack = ::back,
                            scrollState = scrollState,
                            connectedHome = isConnectedHome,
                            showAddDeviceAction = showAdd,
                            menuExpanded = headphoneMenuExpanded,
                            onMenuExpandedChange = { headphoneMenuExpanded = it },
                            onDisconnect = onDisconnect,
                            isSecondary = isSecondary,
                            showBottomBar = showBottomBar,
                            pageContent = shellContent,
                            feedbackContent = shellFeedback,
                        )
                        InterfaceStyle.MATERIAL3 -> MaterialAppShell(
                            page = targetPage,
                            wide = wide,
                            onNavigate = onNavigate,
                            onBack = ::back,
                            scrollState = scrollState,
                            connectedHome = isConnectedHome,
                            showAddDeviceAction = showAdd,
                            menuExpanded = headphoneMenuExpanded,
                            onMenuExpandedChange = { headphoneMenuExpanded = it },
                            onDisconnect = onDisconnect,
                            isSecondary = isSecondary,
                            showBottomBar = showBottomBar,
                            pageContent = shellContent,
                            feedbackContent = shellFeedback,
                        )
                    }
                }
                NavDisplay(
                    backStack = navBackStack,
                    transition = pageTransitionOf(state.settings.pageTransition),
                    onBack = ::back,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    entry<Route.Main> { mainRoute ->
                        renderShell(mainRoute.tab, false)
                    }
                    entry<Route.Noise>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.NOISE, true)
                    }
                    entry<Route.Equalizer>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.EQUALIZER, true)
                    }
                    entry<Route.Touch>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.TOUCH, true)
                    }
                    entry<Route.Audio>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.AUDIO, true)
                    }
                    entry<Route.Details>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.DETAILS, true)
                    }
                    entry<Route.Diagnostics>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.DIAGNOSTICS, true)
                    }
                    entry<Route.AddDevice>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.ADD_DEVICE, true)
                    }
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
