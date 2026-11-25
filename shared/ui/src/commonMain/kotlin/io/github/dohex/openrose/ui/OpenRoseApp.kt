package io.github.dohex.openrose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.ui.diagnostics.SettingOperationsCard
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import io.github.dohex.openrose.ui.model.settingsFor
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@Composable
fun OpenRoseApp(
    model: OpenRoseModel,
    platformUi: PlatformUi,
    modifier: Modifier = Modifier,
    primaryDeviceSelection: PrimaryDeviceSelection = remember { PrimaryDeviceSelection() },
) {
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val backdropActive = rememberBackdropBlurActive(uiSettings)
    val navBackStack = rememberNavBackStack<Route>(Route.Main(AppPage.HEADPHONES))
    val mainTabsPagerState = rememberMainTabsPagerState(initialPage = 0)
    val scrollStates = remember { AppPage.entries.associateWith { ScrollState(0) } }
    val devicePages = remember(state.device?.sessionId) { DevicePageState() }
    val menu = remember { mutableStateOf(false) }
    val animate = uiSettings.pageTransition != PageTransition.NONE
    val navigation = remember(navBackStack, mainTabsPagerState, devicePages, animate) {
        AppNavigation(navBackStack, mainTabsPagerState, devicePages, menu, animate)
    }
    val page = navigation.visiblePage
    val currentNavigation by rememberUpdatedState(navigation)
    val rootFocusRequester = remember { FocusRequester() }
    val uiScope = rememberCoroutineScope()
    val equalizerEditor = remember { EqualizerEditor(uiScope, model::setInSession) }
    val readySessionId = state.readySession?.sessionId
    LaunchedEffect(readySessionId) { equalizerEditor.bind(readySessionId) }
    LaunchedEffect(model.navigation) {
        model.navigation.requests.collect { currentNavigation.openWindow(it) }
    }
    val canGoBack = navigation.canGoBack
    PlatformBackHandler(canGoBack, navigation::back)
    val addDeviceFlow = rememberAddDeviceFlow(model)
    AddDeviceLifecycle(navigation.route.page == AppPage.ADD_DEVICE, state, addDeviceFlow) { navigation.navigate(AppPage.HEADPHONES) }
    LaunchedEffect(state.connection, page) {
        if (page != AppPage.HEADPHONES || state.activeDevice() == null) {
            navigation.menuExpanded = false
        }
    }
    CompositionLocalProvider(
        LocalChoiceDialog provides devicePages.choiceDialog,
        LocalBackdropBlurActive provides backdropActive,
    ) {
        LaunchedEffect(page, navigation.overlayOpen) {
            if (!navigation.overlayOpen) {
                rootFocusRequester.requestFocus()
            }
        }
        BoxWithConstraints(
            modifier.fillMaxSize().focusRequester(rootFocusRequester).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Escape && canGoBack) {
                    navigation.back()
                    true
                } else {
                    false
                }
            }.focusable(),
        ) {
            val wide = maxWidth >= 900.dp
            val pageContentFor: @Composable (AppPage) -> Unit = { targetPage ->
                AppPageContent(
                    targetPage,
                    state,
                    model,
                    platformUi,
                    primaryDeviceSelection,
                    wide,
                    addDeviceFlow,
                    navigation,
                    equalizerEditor,
                )
            }
            val feedbackContentFor: @Composable (AppPage, Modifier) -> Unit = { targetPage, feedbackModifier ->
                PageFeedback(targetPage, state, feedbackModifier, navigation)
            }
            val shell = AppShell(uiSettings, platformUi, navigation, scrollStates, pageContentFor, feedbackContentFor)
            Row(Modifier.fillMaxSize()) {
                if (wide) shell.Rail()
                AppRouteStack(
                    uiSettings.pageTransition,
                    navigation,
                    Modifier.weight(1f),
                    renderMainShell = { shell.Main(wide, state.activeDevice() != null, model::disconnect) },
                    renderShell = { shell.Secondary(it) },
                )
            }
        }
    }
}

internal val AppPage.navigationLabel: String
    get() = when (this) {
        AppPage.HEADPHONES -> "耳机"
        else -> title
    }

private class DevicePageState {
    val choiceDialog = mutableStateOf<String?>(null)
    var selectedTouchSlot by mutableStateOf<TouchSlot?>(null)
    var selectedTouchSide by mutableStateOf<String?>(null)
}

private class AppNavigation(
    val stack: NavBackStack,
    val tabs: MainTabsPagerState,
    val device: DevicePageState,
    menu: MutableState<Boolean>,
    val animate: Boolean,
) {
    var menuExpanded by menu

    val route: Route
        get() = stack.lastOrNull() as? Route ?: Route.Main()

    val selectedTab: AppPage
        get() = mainPages[tabs.selectedPage]

    val visiblePage: AppPage
        get() = if (route is Route.Main) selectedTab else route.page

    val overlayOpen: Boolean
        get() = menuExpanded || device.selectedTouchSlot != null || device.choiceDialog.value != null

    val canGoBack: Boolean
        get() = overlayOpen || stack.size > 1 || (route is Route.Main && tabs.selectedPage != 0)

    private fun clearOverlays() {
        menuExpanded = false
        device.choiceDialog.value = null
        device.selectedTouchSlot = null
    }

    private fun returnToTabs() {
        while (stack.size > 1) stack.removeLastOrNull()
    }

    fun openWindow(destination: WindowDestination) {
        returnToTabs()
        tabs.selectPage(0, animate = false)
        clearOverlays()
        when (destination) {
            WindowDestination.ADD_DEVICE -> stack.add(Route.Secondary(AppPage.ADD_DEVICE))

            WindowDestination.DIAGNOSTICS -> stack.add(Route.Secondary(AppPage.DIAGNOSTICS))

            WindowDestination.TRAY_PANEL_SETTINGS -> {
                tabs.selectPage(mainPages.indexOf(AppPage.SETTINGS), animate = false)
                stack.add(Route.Secondary(AppPage.TRAY_PANEL_SETTINGS))
            }

            WindowDestination.HEADPHONES -> Unit
        }
    }

    fun navigate(destination: AppPage) {
        clearOverlays()
        val tab = mainPages.indexOf(destination)
        if (tab >= 0) {
            val showingTab = stack.size == 1 && tab == tabs.selectedPage
            returnToTabs()
            if (showingTab) tabs.requestScrollToTop(tab) else tabs.selectPage(tab, animate = animate)
        } else if ((stack.lastOrNull() as? Route)?.page != destination) {
            stack.add(Route.Secondary(destination))
        }
    }

    fun back() {
        when {
            menuExpanded -> menuExpanded = false
            device.selectedTouchSlot != null -> device.selectedTouchSlot = null
            device.choiceDialog.value != null -> device.choiceDialog.value = null
            stack.size > 1 -> stack.removeLastOrNull()
            tabs.selectedPage != 0 -> tabs.selectPage(0, animate = animate)
        }
    }
}

@Composable
private fun AppPageContent(
    targetPage: AppPage,
    state: ControlState,
    model: OpenRoseModel,
    platformUi: PlatformUi,
    selection: PrimaryDeviceSelection,
    wide: Boolean,
    addDeviceFlow: AddDeviceFlow,
    navigation: AppNavigation,
    equalizerEditor: EqualizerEditor,
) {
    when (targetPage) {
        AppPage.HEADPHONES -> HeadphonesPage(model, platformUi, navigation::navigate, selection)

        AppPage.ADD_DEVICE -> AddDeviceContent(
            state = state,
            canScan = state.canSearchForHeadphones(),
            selectedDeviceId = addDeviceFlow.selectedDeviceId,
            connectionPhase = addDeviceFlow.phase(state.connection),
            connectionError = addDeviceFlow.connectionError,
            startScanError = addDeviceFlow.scanError,
            scanTimedOut = addDeviceFlow.scanTimedOut,
            onRequestPermission = platformUi.requestBluetoothPermission,
            onOpenSystemSettings = platformUi.openBluetoothSettings,
            onStartScan = addDeviceFlow::startSearch,
            onStopScan = addDeviceFlow::stopSearch,
            onConnect = addDeviceFlow::connect,
            onCancelConnection = addDeviceFlow::cancelConnection,
            onDiagnostics = { navigation.navigate(AppPage.DIAGNOSTICS) },
        )

        AppPage.STATUS -> StatusPage(
            model = model,
            platformUi = platformUi,
            onDiagnostics = { navigation.navigate(AppPage.DIAGNOSTICS) },
        )

        AppPage.SETTINGS -> PreferencesPage(
            model = model,
            platformUi = platformUi,
            onPersonalization = { navigation.navigate(AppPage.PERSONALIZATION) },
            onCirculationPairing = { navigation.navigate(AppPage.CIRCULATION_PAIRING) },
            onTrayPanelSettings = { navigation.navigate(AppPage.TRAY_PANEL_SETTINGS) },
            onSystemIntegration = { navigation.navigate(AppPage.SYSTEM_INTEGRATION) },
            onAbout = { navigation.navigate(AppPage.ABOUT) },
        )

        AppPage.PERSONALIZATION -> PersonalizationPage(model, platformUi, wide = wide)

        AppPage.CIRCULATION_PAIRING -> PlatformRoutePage(AppPage.CIRCULATION_PAIRING, platformUi.circulationPairingContent, navigation)

        AppPage.TRAY_PANEL_SETTINGS -> PlatformRoutePage(AppPage.TRAY_PANEL_SETTINGS, platformUi.trayPanelSettingsContent, navigation)

        AppPage.SYSTEM_INTEGRATION -> PlatformRoutePage(AppPage.SYSTEM_INTEGRATION, platformUi.systemIntegrationContent, navigation)

        AppPage.ABOUT -> AboutPage()

        AppPage.DIAGNOSTICS -> DiagnosticsPage(model, platformUi, state)

        AppPage.TOUCH, AppPage.EQUALIZER, AppPage.AUDIO, AppPage.HEADSET_INFO -> HeadsetPage(state, targetPage, navigation) { device ->
            HeadsetPageContent(targetPage, state, device, model, navigation, equalizerEditor)
        }
    }
}

@Composable
private fun HeadsetPageContent(
    targetPage: AppPage,
    state: ControlState,
    device: DeviceState,
    model: OpenRoseModel,
    navigation: AppNavigation,
    equalizerEditor: EqualizerEditor,
) {
    when (targetPage) {
        AppPage.TOUCH -> TouchControlCard(
            state,
            device,
            navigation.device.selectedTouchSlot,
            { navigation.device.selectedTouchSlot = it },
            model.settingsFor(device.sessionId),
            navigation.device.selectedTouchSide,
            { navigation.device.selectedTouchSide = it },
        )

        AppPage.EQUALIZER -> EqualizerPage(state, device, equalizerEditor)

        AppPage.AUDIO -> DeviceControlCard(state, device, model.settingsFor(device.sessionId))

        AppPage.HEADSET_INFO -> HeadsetInfo(state, device, model::disconnect) { navigation.navigate(AppPage.DIAGNOSTICS) }

        else -> Unit
    }
}

@Composable
private fun HeadsetPage(
    state: ControlState,
    page: AppPage,
    navigation: AppNavigation,
    content: @Composable (DeviceState) -> Unit,
) {
    val device = state.activeDevice()
    if (device == null) {
        AppGroup {
            AppInfoRow("耳机已断开", "连接耳机后，可查看和调整当前型号支持的功能。")
            AppButton("添加耳机", { navigation.navigate(AppPage.ADD_DEVICE) })
        }
    } else {
        if (page.editsHeadset) SettingPageBanner(state, device)
        content(device)
    }
}

@Composable
private fun DiagnosticsPage(model: OpenRoseModel, platformUi: PlatformUi, state: ControlState) {
    PlatformDiagnosticsCard(model, platformUi)
    ConnectionDetailsCard(state)
    SettingOperationsCard(state)
    state.activeDevice()?.let { DeviceReadings(it, state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING) }
}

@Composable
private fun PlatformRoutePage(
    page: AppPage,
    content: (@Composable () -> Unit)?,
    navigation: AppNavigation,
) {
    if (content != null) {
        content()
    } else {
        LaunchedEffect(page) { if (navigation.route.page == page) navigation.back() }
    }
}

@Composable
private fun PageFeedback(targetPage: AppPage, state: ControlState, feedbackModifier: Modifier, navigation: AppNavigation) {
    val device = state.activeDevice()
    if (targetPage.editsHeadset || (targetPage == AppPage.HEADPHONES && device != null)) {
        key(targetPage) {
            SettingFeedback(
                state,
                device,
                showPending = { setting ->
                    when (targetPage) {
                        AppPage.HEADPHONES -> setting != DeviceSetting.GAME_MODE && setting != DeviceSetting.NOISE_MODE
                        AppPage.EQUALIZER -> false
                        else -> true
                    }
                },
                modifier = feedbackModifier,
            ) {
                navigation.navigate(AppPage.DIAGNOSTICS)
            }
        }
    }
}

@Composable
private fun AppRouteStack(
    transition: PageTransition,
    navigation: AppNavigation,
    modifier: Modifier,
    renderMainShell: @Composable () -> Unit,
    renderShell: @Composable (AppPage) -> Unit,
) {
    NavDisplay(
        backStack = navigation.stack,
        transition = pageTransitionOf(transition),
        onBack = navigation::back,
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        entry<Route.Main> {
            Box(if (navigation.route is Route.Main) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }) {
                renderMainShell()
            }
        }
        entry<Route.Secondary>(swipeDismiss = NavSwipeDirection.LeftToRight) { pushed ->
            if (pushed.page == AppPage.ADD_DEVICE) {
                val onTop = navigation.route.page == AppPage.ADD_DEVICE
                if (onTop || navigation.animate) {
                    Box(if (onTop) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics { }) {
                        renderShell(pushed.page)
                    }
                }
            } else {
                renderShell(pushed.page)
            }
        }
    }
}

private class AppShell(
    private val uiSettings: UiSettings,
    private val platformUi: PlatformUi,
    private val navigation: AppNavigation,
    private val scrollStates: Map<AppPage, ScrollState>,
    private val pageContentFor: @Composable (AppPage) -> Unit,
    private val feedbackContentFor: @Composable (AppPage, Modifier) -> Unit,
) {
    @Composable
    private fun Theme(content: @Composable () -> Unit) {
        ProvideInterfaceStyle(uiSettings.interfaceStyle, uiSettings.appearance.isDark(), platformUi.dynamicColor, content)
    }

    @Composable
    fun Main(wide: Boolean, connectedHome: Boolean, onDisconnect: () -> Unit) {
        Theme {
            val animatePageChanges = navigation.animate
            val onNavigate: (AppPage) -> Unit = navigation::navigate
            val platformActions: @Composable (AppPage) -> Unit = { actionPage ->
                when (actionPage) {
                    AppPage.STATUS -> platformUi.StatusActions()
                    AppPage.HEADPHONES -> platformUi.HeadphoneActions()
                    else -> Unit
                }
            }

            when (uiSettings.interfaceStyle) {
                InterfaceStyle.MIUIX -> MiuixMainShell(
                    pagerState = navigation.tabs,
                    wide = wide,
                    bottomBarStyle = uiSettings.bottomBarStyle,
                    floatingBarMaterial = uiSettings.floatingBarMaterial,
                    onNavigate = onNavigate,
                    scrollStates = scrollStates,
                    pageContentFor = pageContentFor,
                    pageFeedbackFor = feedbackContentFor,
                    connectedHome = connectedHome,
                    menuExpanded = navigation.menuExpanded,
                    onMenuExpandedChange = { navigation.menuExpanded = it },
                    onDisconnect = onDisconnect,
                    platformActions = platformActions,
                    animatePageChanges = animatePageChanges,
                )

                InterfaceStyle.MATERIAL3 -> MaterialMainShell(
                    pagerState = navigation.tabs,
                    wide = wide,
                    bottomBarStyle = uiSettings.bottomBarStyle,
                    onNavigate = onNavigate,
                    scrollStates = scrollStates,
                    pageContentFor = pageContentFor,
                    pageFeedbackFor = feedbackContentFor,
                    connectedHome = connectedHome,
                    menuExpanded = navigation.menuExpanded,
                    onMenuExpandedChange = { navigation.menuExpanded = it },
                    onDisconnect = onDisconnect,
                    platformActions = platformActions,
                    animatePageChanges = animatePageChanges,
                )
            }
        }
    }

    @Composable
    fun Secondary(targetPage: AppPage) {
        Theme {
            val shellContent: @Composable () -> Unit = { pageContentFor(targetPage) }
            val shellFeedback: @Composable (Modifier) -> Unit = { feedbackContentFor(targetPage, it) }
            when (uiSettings.interfaceStyle) {
                InterfaceStyle.MIUIX -> MiuixAppShell(
                    page = targetPage,
                    onBack = navigation::back,
                    scrollState = scrollStates.getValue(targetPage),
                    pageContent = shellContent,
                    feedbackContent = shellFeedback,
                )

                InterfaceStyle.MATERIAL3 -> MaterialAppShell(
                    page = targetPage,
                    onBack = navigation::back,
                    scrollState = scrollStates.getValue(targetPage),
                    pageContent = shellContent,
                    feedbackContent = shellFeedback,
                )
            }
        }
    }

    @Composable
    fun Rail() {
        val railMotion = navigation.animate
        AnimatedVisibility(
            visible = navigation.route.page != AppPage.ADD_DEVICE,
            enter = if (railMotion) expandHorizontally(expandFrom = Alignment.Start) else EnterTransition.None,
            exit = if (railMotion) shrinkHorizontally(shrinkTowards = Alignment.Start) else ExitTransition.None,
        ) {
            Theme {
                when (uiSettings.interfaceStyle) {
                    InterfaceStyle.MIUIX -> MiuixNavigationRail(navigation.selectedTab, navigation::navigate)
                    InterfaceStyle.MATERIAL3 -> MaterialNavigationRail(navigation.selectedTab, navigation::navigate)
                }
            }
        }
    }
}
