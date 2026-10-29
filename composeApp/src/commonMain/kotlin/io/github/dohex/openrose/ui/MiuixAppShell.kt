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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.*

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
    Row(Modifier.fillMaxSize()) {
        if (wide) NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
            mainPages.forEach { destination ->
                val isSelected = if (backStack.isNotEmpty()) backStack.first() == destination else page == destination
                NavigationRailItem(selected = isSelected,
                    onClick = { onNavigate(destination) }, icon = RoseIcons[destination.icon], label = destination.title)
            }
        }
        val scrollBehavior = MiuixScrollBehavior()
        val title = if (page == AppPage.HEADPHONES && !wide) "OpenRose" else page.title
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
                if (page == AppPage.HEADPHONES && !connectedHome) {
                    SmallTopAppBar(title = title, scrollBehavior = scrollBehavior, navigationIcon = navigation, actions = actions)
                } else {
                    TopAppBar(title = title, largeTitle = title, scrollBehavior = scrollBehavior, navigationIcon = navigation, actions = actions)
                }
            },
            bottomBar = {
                if (showBottomBar) NavigationBar {
                    mainPages.forEach { destination -> NavigationBarItem(selected = page == destination,
                        onClick = { onNavigate(destination) }, icon = RoseIcons[destination.icon], label = destination.navigationLabel) }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    ShellPageContent(page, padding, compactHome = page == AppPage.HEADPHONES && !connectedHome, pageContent)
                }
                ShellFeedback(padding, feedbackContent)
            }
        }
    }
}

@Composable
internal fun MiuixMainShell(
    pagerState: MainTabsPagerState,
    wide: Boolean,
    onNavigate: (AppPage) -> Unit,
    scrollStates: Map<AppPage, ScrollState>,
    pageContentFor: @Composable (AppPage) -> Unit,
    pageFeedbackFor: @Composable (AppPage) -> Unit = {},
    connectedHome: Boolean = false,
    showAddDeviceAction: Boolean = true,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onDisconnect: () -> Unit = {},
    animatedTabTransition: Boolean = true,
) {
    LaunchedEffect(pagerState.pagerState.currentPage) {
        pagerState.syncPage()
    }
    val currentTab = mainPages.getOrElse(pagerState.selectedPage) { AppPage.HEADPHONES }
    Row(Modifier.fillMaxSize()) {
        if (wide) {
            NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
                mainPages.forEachIndexed { index, destination ->
                    val isSelected = currentTab == destination
                    NavigationRailItem(
                        selected = isSelected,
                        onClick = {
                            pagerState.selectPage(index, animated = animatedTabTransition)
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
                    NavigationBar {
                        mainPages.forEachIndexed { index, destination ->
                            NavigationBarItem(
                                selected = currentTab == destination,
                                onClick = {
                                    pagerState.selectPage(index, animated = animatedTabTransition)
                                    onNavigate(destination)
                                },
                                icon = RoseIcons[destination.icon],
                                label = destination.navigationLabel,
                            )
                        }
                    }
                }
            },
        ) { outerPadding ->
            HorizontalPager(
                state = pagerState.pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = outerPadding.calculateBottomPadding()),
                beyondViewportPageCount = 1,
                userScrollEnabled = !wide,
                key = { index -> mainPages[index] },
            ) { pageIndex ->
                val page = mainPages[pageIndex]
                val isSelected = pageIndex == pagerState.selectedPage
                val pageModifier = if (isSelected) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().clearAndSetSemantics { }.focusProperties {
                        canFocus = false
                        enter = { FocusRequester.Cancel }
                    }
                }
                Box(pageModifier) {
                    val scrollBehavior = MiuixScrollBehavior()
                    val title = if (page == AppPage.HEADPHONES && !wide) "OpenRose" else page.title
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
                        if (page == AppPage.HEADPHONES && !connectedHome) {
                            SmallTopAppBar(title = title, scrollBehavior = scrollBehavior, actions = actions)
                        } else {
                            TopAppBar(title = title, largeTitle = title, scrollBehavior = scrollBehavior, actions = actions)
                        }
                    },
                ) { pagePadding ->
                    Box(Modifier.fillMaxSize()) {
                        val scrollState = scrollStates.getValue(page)
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                            ShellPageContent(page, pagePadding, compactHome = page == AppPage.HEADPHONES && !connectedHome) {
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
