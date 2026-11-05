package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.ui.model.OpenRoseModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

internal enum class AppPage(val title: String, val icon: RoseIcon) {
    HEADPHONES("我的耳机", RoseIcon.HEADPHONES),
    STATUS("状态", RoseIcon.STATUS),
    SETTINGS("设置", RoseIcon.SETTINGS),
    ADD_DEVICE("添加耳机", RoseIcon.PLUS),
    TOUCH("触控操作", RoseIcon.TOUCH),
    AUDIO("音频与设备", RoseIcon.AUDIO),
    DETAILS("设备详情", RoseIcon.INFO),
    DIAGNOSTICS("诊断与记录", RoseIcon.LOG),
}


internal val mainPages = listOf(AppPage.HEADPHONES, AppPage.STATUS, AppPage.SETTINGS)

/** Navigation, scroll positions and drafts survive replacement of either component style.
 * The host remains the sole owner of the control session and transport. */
@Composable
fun OpenRoseApp(
    model: OpenRoseModel,
    platformUi: PlatformUi,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val homeNavigationRequest by model.homeNavigationRequest.collectAsState()
    val onDisconnect = model::disconnect
    val onSet = model::set
    val dark = when (uiSettings.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val backdropActive = rememberBackdropBlurActive(uiSettings)
    val navBackStack = rememberNavBackStack<Route>(Route.Main(AppPage.HEADPHONES))
    val mainTabsPagerState = rememberMainTabsPagerState(initialPage = 0)
    val currentRoute = (navBackStack.lastOrNull() as? Route) ?: Route.Main(AppPage.HEADPHONES)
    val latestRoute by rememberUpdatedState(currentRoute)
    val page = when (currentRoute) {
        is Route.Main -> mainPages.getOrElse(mainTabsPagerState.selectedPage) { AppPage.HEADPHONES }
        else -> currentRoute.toAppPage()
    }
    val scrollStates = remember { AppPage.entries.associateWith { ScrollState(0) } }
    var selectedTouchSlot by remember(state.device?.sessionId) { mutableStateOf<TouchSlot?>(null) }
    var headphoneMenuExpanded by remember { mutableStateOf(false) }
    val rootFocusRequester = remember { FocusRequester() }
    val choiceDialog = remember(state.device?.sessionId) { mutableStateOf<String?>(null) }
    var selectedTouchSide by remember(state.device?.sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(homeNavigationRequest) {
        if (homeNavigationRequest != 0L) {
            while (navBackStack.size > 1) {
                navBackStack.removeLastOrNull()
            }
            mainTabsPagerState.selectPage(0, transition = PageTransition.NONE)
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
            while (navBackStack.size > 1) {
                navBackStack.removeLastOrNull()
            }
            val targetIndex = mainPages.indexOf(destination)
            if (targetIndex >= 0) {
                mainTabsPagerState.selectPage(targetIndex, transition = uiSettings.pageTransition)
            }
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
            mainTabsPagerState.selectedPage != 0 -> {
                mainTabsPagerState.selectPage(0, transition = uiSettings.pageTransition)
            }
        }
    }
    val canGoBack = headphoneMenuExpanded ||
        selectedTouchSlot != null ||
        choiceDialog.value != null ||
        navBackStack.size > 1 ||
        (currentRoute is Route.Main && mainTabsPagerState.selectedPage != 0)
    PlatformBackHandler(
        canGoBack,
        ::back,
    )
    val addDeviceFlow = rememberAddDeviceFlow(
        active = currentRoute is Route.AddDevice,
        state = state,
        onStartScan = model::startScan,
        onStopScan = model::stopScan,
        onConnect = model::connectForAddDevice,
        onReady = { navigate(AppPage.HEADPHONES) },
    )
    LaunchedEffect(state.connection, page) {
        if (page != AppPage.HEADPHONES || state.activeDevice() == null) {
            headphoneMenuExpanded = false
        }
    }
    CompositionLocalProvider(
        LocalChoiceDialog provides choiceDialog,
        LocalBackdropBlurActive provides backdropActive,
    ) {
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
                        AppPage.HEADPHONES -> HeadphonesPage(model, platformUi, onNavigate)
                        AppPage.ADD_DEVICE -> AddDeviceContent(
                            state = state,
                            canScan = state.canSearchForHeadphones(),
                            selectedDeviceId = addDeviceFlow.selectedDeviceId,
                            connectionPhase = addDeviceFlow.phase(state.connection),
                            connectionError = addDeviceFlow.connectionError,
                            onRequestPermission = platformUi.requestBluetoothPermission,
                            onOpenSystemSettings = platformUi.openBluetoothSettings,
                            onStartScan = addDeviceFlow::startSearch,
                            onStopScan = addDeviceFlow::stopSearch,
                            onConnect = addDeviceFlow::connect,
                            onCancelConnection = addDeviceFlow::cancelConnection,
                            onDiagnostics = { navigate(AppPage.DIAGNOSTICS) },
                        )
                        AppPage.STATUS -> StatusPage(
                            model = model,
                            platformUi = platformUi,
                            onDiagnostics = { navigate(AppPage.DIAGNOSTICS) },
                        )
                        AppPage.SETTINGS -> PreferencesPage(model, platformUi)
                        AppPage.DIAGNOSTICS -> {
                            PlatformDiagnosticsCard(model, platformUi)
                            ConnectionDetailsCard(state, onDisconnect)
                            SettingOperationsCard(state)
                            device?.let { DeviceReadings(it, state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING) }
                        }
                        else -> if (device != null) {
                            val controlPage = targetPage in listOf(AppPage.TOUCH, AppPage.AUDIO)
                            if (controlPage) {
                                SettingPageBanner(state, device)
                            }
                            when (targetPage) {
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
                        targetPage in listOf(AppPage.TOUCH, AppPage.AUDIO)) {
                        key(targetPage) {
                            SettingFeedback(state, device) { navigate(AppPage.DIAGNOSTICS) }
                        }
                    }
                }
                val renderShell: @Composable (AppPage) -> Unit = { targetPage ->
                    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark) {
                        val parentTab = mainPages.getOrElse(mainTabsPagerState.selectedPage) { AppPage.HEADPHONES }
                        val shellContent: @Composable () -> Unit = { pageContentFor(targetPage) }
                        val shellFeedback: @Composable () -> Unit = { feedbackContentFor(targetPage) }
                        val shellWide = wide && targetPage != AppPage.ADD_DEVICE

                        when (uiSettings.interfaceStyle) {
                            InterfaceStyle.MIUIX -> MiuixAppShell(
                                parentTab = parentTab,
                                page = targetPage,
                                wide = shellWide,
                                onNavigate = onNavigate,
                                onBack = ::back,
                                scrollState = scrollStates.getValue(targetPage),
                                pageContent = shellContent,
                                feedbackContent = shellFeedback,
                            )
                            InterfaceStyle.MATERIAL3 -> MaterialAppShell(
                                parentTab = parentTab,
                                page = targetPage,
                                wide = shellWide,
                                onNavigate = onNavigate,
                                onBack = ::back,
                                scrollState = scrollStates.getValue(targetPage),
                                pageContent = shellContent,
                                feedbackContent = shellFeedback,
                            )
                        }
                    }
                }
                val renderMainShell: @Composable () -> Unit = {
                    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark) {
                        val isConnectedHome = state.activeDevice() != null
                        val pageTransition = uiSettings.pageTransition
                        val onNavigate: (AppPage) -> Unit = { navigate(it) }
                        val platformActions: @Composable (AppPage) -> Unit = { actionPage ->
                            when (actionPage) {
                                AppPage.STATUS -> platformUi.StatusActions()
                                AppPage.HEADPHONES -> platformUi.HeadphoneActions()
                                else -> Unit
                            }
                        }

                        when (uiSettings.interfaceStyle) {
                            InterfaceStyle.MIUIX -> MiuixMainShell(
                                pagerState = mainTabsPagerState,
                                wide = wide,
                                bottomBarStyle = uiSettings.bottomBarStyle,
                                onNavigate = onNavigate,
                                scrollStates = scrollStates,
                                pageContentFor = { targetPage -> pageContentFor(targetPage) },
                                pageFeedbackFor = { targetPage -> feedbackContentFor(targetPage) },
                                connectedHome = isConnectedHome,
                                menuExpanded = headphoneMenuExpanded,
                                onMenuExpandedChange = { headphoneMenuExpanded = it },
                                onDisconnect = onDisconnect,
                                platformActions = platformActions,
                                pageTransition = pageTransition,
                            )
                            InterfaceStyle.MATERIAL3 -> MaterialMainShell(
                                pagerState = mainTabsPagerState,
                                wide = wide,
                                bottomBarStyle = uiSettings.bottomBarStyle,
                                onNavigate = onNavigate,
                                scrollStates = scrollStates,
                                pageContentFor = { targetPage -> pageContentFor(targetPage) },
                                pageFeedbackFor = { targetPage -> feedbackContentFor(targetPage) },
                                connectedHome = isConnectedHome,
                                menuExpanded = headphoneMenuExpanded,
                                onMenuExpandedChange = { headphoneMenuExpanded = it },
                                onDisconnect = onDisconnect,
                                platformActions = platformActions,
                                pageTransition = pageTransition,
                            )
                        }
                    }
                }
                NavDisplay(
                    backStack = navBackStack,
                    transition = pageTransitionOf(uiSettings.pageTransition),
                    onBack = ::back,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    entry<Route.Main> {
                        Box(if (currentRoute is Route.Main) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }) {
                            renderMainShell()
                        }
                    }
                    entry<Route.Touch>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.TOUCH)
                    }
                    entry<Route.Audio>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.AUDIO)
                    }
                    entry<Route.Details>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.DETAILS)
                    }
                    entry<Route.Diagnostics>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        renderShell(AppPage.DIAGNOSTICS)
                    }
                    entry<Route.AddDevice>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                        // Popped entries retain their registration while animating out.
                        if (latestRoute is Route.AddDevice || uiSettings.pageTransition != PageTransition.NONE) {
                            Box(if (latestRoute is Route.AddDevice) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }) {
                                renderShell(AppPage.ADD_DEVICE)
                            }
                        }
                    }
                }
            }
        }
    }

internal val AppPage.navigationLabel: String
    get() = when (this) {
        AppPage.HEADPHONES -> "耳机"
        else -> title
    }
