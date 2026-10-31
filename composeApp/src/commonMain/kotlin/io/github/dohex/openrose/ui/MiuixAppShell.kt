@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.*
import io.github.dohex.openrose.core.PageTransition
import io.github.dohex.openrose.core.BottomBarStyle
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
@Composable
internal fun MiuixAppShell(
    backStack: List<AppPage> = emptyList(),
    page: AppPage,
    wide: Boolean,
    onNavigate: (AppPage) -> Unit,
    onBack: () -> Unit,
    scrollState: ScrollState,
    pageContent: @Composable () -> Unit,
    feedbackContent: @Composable () -> Unit = {},
    connectedHome: Boolean = false,
    showAddDeviceAction: Boolean = true,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onDisconnect: () -> Unit = {},
    isSecondary: Boolean = backStack.size > 1 || page !in mainPages,
    showBottomBar: Boolean = !wide && !isSecondary && page in mainPages,
) {
    val backdropActive = LocalBackdropBlurActive.current
    val backdrop = rememberAppBackdrop(MiuixTheme.colorScheme.surface, backdropActive)
    CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
        Row(Modifier.fillMaxSize()) {
        if (wide) NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
            mainPages.forEach { destination ->
                val isSelected = if (backStack.isNotEmpty()) backStack.first() == destination else page == destination
                NavigationRailItem(selected = isSelected,
                    onClick = { onNavigate(destination) }, icon = RoseIcons[destination.icon], label = destination.title)
            }
        }
        val scrollBehavior = MiuixScrollBehavior()
        val title = page.title
        val navigation: @Composable () -> Unit = {
            if (isSecondary) IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") }
        }
        val actions: @Composable RowScope.() -> Unit = {
            if (page == AppPage.HEADPHONES) {
                Box {
                    if (connectedHome) {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(RoseIcons[RoseIcon.MORE], "耳机菜单")
                        }
                    } else if (showAddDeviceAction) {
                        IconButton(onClick = { onNavigate(AppPage.ADD_DEVICE) }) {
                            Icon(RoseIcons.Plus, "添加耳机")
                        }
                    }
                    OverlayListPopup(
                        show = connectedHome && menuExpanded,
                        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                        alignment = PopupPositionProvider.Align.TopEnd,
                        enableWindowDim = false,
                        onDismissRequest = { onMenuExpandedChange(false) },
                    ) {
                        ListPopupColumn {
                            BasicComponent(
                                title = "断开控制连接",
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onDisconnect()
                                },
                                role = Role.Button,
                            )
                        }
                    }
                }
            }
        }
        Scaffold(modifier = Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                val topBarModifier = Modifier.appBarBackdropBlur(
                    backdrop = backdrop,
                    surfaceColor = MiuixTheme.colorScheme.surface,
                    active = backdropActive,
                )
                val topBarColor = if (backdropActive) Color.Transparent else MiuixTheme.colorScheme.surface
                TopAppBar(title = title, largeTitle = title, modifier = topBarModifier, color = topBarColor, scrollBehavior = scrollBehavior, navigationIcon = navigation, actions = actions)
            },
            bottomBar = {
                if (showBottomBar) {
                    val bottomBarModifier = Modifier.appBarBackdropBlur(
                        backdrop = backdrop,
                        surfaceColor = MiuixTheme.colorScheme.surface,
                        active = backdropActive,
                    )
                    val bottomBarColor = if (backdropActive) Color.Transparent else MiuixTheme.colorScheme.surface
                    NavigationBar(modifier = bottomBarModifier, color = bottomBarColor) {
                        mainPages.forEach { destination -> NavigationBarItem(selected = page == destination,
                            onClick = { onNavigate(destination) }, icon = RoseIcons[destination.icon], label = destination.navigationLabel) }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().appBackdropContent(backdrop, backdropActive).verticalScroll(scrollState)) {
                    ShellPageContent(page, padding, compactHome = page == AppPage.HEADPHONES && !connectedHome, pageContent = pageContent)
                }
                ShellFeedback(padding, feedbackContent)
            }
        }
        }
    }
}

@Composable
internal fun MiuixMainShell(
    pagerState: MainTabsPagerState,
    wide: Boolean,
    bottomBarStyle: BottomBarStyle = BottomBarStyle.FIXED,
    onNavigate: (AppPage) -> Unit,
    scrollStates: Map<AppPage, ScrollState>,
    pageContentFor: @Composable (AppPage) -> Unit,
    pageFeedbackFor: @Composable (AppPage) -> Unit = {},
    connectedHome: Boolean = false,
    showAddDeviceAction: Boolean = true,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onDisconnect: () -> Unit = {},
    pageTransition: PageTransition = PageTransition.MIUIX_SPRING,
) {
    LaunchedEffect(pagerState.pagerState.currentPage) {
        pagerState.syncPage()
    }
    val backdropActive = LocalBackdropBlurActive.current
    val currentTab = mainPages.getOrElse(pagerState.selectedPage) { AppPage.HEADPHONES }
    Row(Modifier.fillMaxSize()) {
        if (wide) {
            NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
                mainPages.forEachIndexed { index, destination ->
                    val isSelected = currentTab == destination
                    NavigationRailItem(
                        selected = isSelected,
                        onClick = {
                            pagerState.selectPage(index, transition = pageTransition)
                            onNavigate(destination)
                        },
                        icon = RoseIcons[destination.icon],
                        label = destination.title,
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            bottomBar = {
                if (!wide) {
                    if (bottomBarStyle == BottomBarStyle.FLOATING) {
                        FloatingNavigationBar {
                            mainPages.forEachIndexed { index, destination ->
                                FloatingNavigationBarItem(
                                    selected = currentTab == destination,
                                    onClick = {
                                        pagerState.selectPage(index, transition = pageTransition)
                                        onNavigate(destination)
                                    },
                                    icon = RoseIcons[destination.icon],
                                    label = destination.navigationLabel,
                                )
                            }
                        }
                    } else {
                        NavigationBar {
                            mainPages.forEachIndexed { index, destination ->
                                NavigationBarItem(
                                    selected = currentTab == destination,
                                    onClick = {
                                        pagerState.selectPage(index, transition = pageTransition)
                                        onNavigate(destination)
                                    },
                                    icon = RoseIcons[destination.icon],
                                    label = destination.navigationLabel,
                                )
                            }
                        }
                    }
                }
            },
        ) { outerPadding ->
            val isFloating = !wide && bottomBarStyle == BottomBarStyle.FLOATING
            val pagerBottomPadding = if (isFloating) 0.dp else outerPadding.calculateBottomPadding()
            val bottomClearance = if (isFloating) outerPadding.calculateBottomPadding() else 0.dp
            HorizontalPager(
                state = pagerState.pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = pagerBottomPadding)
                    .pagerGestureOverride(
                        pagerState = pagerState.pagerState,
                        enabled = !wide,
                    ),
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                key = { index -> mainPages[index] },
            ) { pageIndex ->
                val page = mainPages[pageIndex]
                val isSelected = pageIndex == pagerState.selectedPage
                val pageBackdrop = rememberAppBackdrop(MiuixTheme.colorScheme.surface, backdropActive)
                val pageModifier = if (isSelected) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().clearAndSetSemantics { }.focusProperties {
                        canFocus = false
                        enter = { FocusRequester.Cancel }
                    }
                }
                CompositionLocalProvider(LocalAppBackdrop provides pageBackdrop) {
                Box(pageModifier) {
                    val scrollBehavior = MiuixScrollBehavior()
                    val title = page.title
                    val actions: @Composable RowScope.() -> Unit = {
                        if (page == AppPage.HEADPHONES && isSelected) {
                            Box {
                            if (connectedHome) {
                                IconButton(onClick = { onMenuExpandedChange(true) }) {
                                    Icon(RoseIcons[RoseIcon.MORE], "耳机菜单")
                                }
                            } else if (showAddDeviceAction) {
                                IconButton(onClick = { onNavigate(AppPage.ADD_DEVICE) }) {
                                    Icon(RoseIcons.Plus, "添加耳机")
                                }
                            }
                            OverlayListPopup(
                                show = connectedHome && menuExpanded,
                                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                                alignment = PopupPositionProvider.Align.TopEnd,
                                enableWindowDim = false,
                                onDismissRequest = { onMenuExpandedChange(false) },
                            ) {
                                ListPopupColumn {
                                    BasicComponent(
                                        title = "断开控制连接",
                                        onClick = {
                                            onMenuExpandedChange(false)
                                            onDisconnect()
                                        },
                                        role = Role.Button,
                                    )
                                }
                            }
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        val topBarModifier = Modifier.appBarBackdropBlur(
                            backdrop = pageBackdrop,
                            surfaceColor = MiuixTheme.colorScheme.surface,
                            active = backdropActive,
                        )
                        val topBarColor = if (backdropActive) Color.Transparent else MiuixTheme.colorScheme.surface
                        TopAppBar(title = title, largeTitle = title, modifier = topBarModifier, color = topBarColor, scrollBehavior = scrollBehavior, actions = actions)
                    },
                ) { pagePadding ->
                    Box(Modifier.fillMaxSize()) {
                        val scrollState = scrollStates.getValue(page)
                        Column(Modifier.fillMaxSize().appBackdropContent(pageBackdrop, backdropActive).verticalScroll(scrollState)) {
                            ShellPageContent(
                                page = page,
                                padding = pagePadding,
                                compactHome = page == AppPage.HEADPHONES && !connectedHome,
                                bottomClearance = bottomClearance,
                            ) {
                                pageContentFor(page)
                            }
                        }
                        ShellFeedback(pagePadding) {
                            if (isSelected) {
                                pageFeedbackFor(page)
                            }
                        }
                    }
                }
                }
                }
            }
        }
    }
}
