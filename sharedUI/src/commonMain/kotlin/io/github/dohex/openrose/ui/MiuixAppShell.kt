@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/** The wide-screen rail. The app root places it beside the route stack so page transitions never move it. */
@Composable
internal fun MiuixNavigationRail(selected: AppPage, onSelect: (AppPage) -> Unit) {
    NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
        mainPages.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = RoseIcons[destination.icon],
                label = destination.title,
            )
        }
    }
}

/** Renders a pushed (secondary) page; main tabs live in [MiuixMainShell]. */
@Composable
internal fun MiuixAppShell(
    page: AppPage,
    onBack: () -> Unit,
    scrollState: ScrollState,
    pageContent: @Composable () -> Unit,
    feedbackContent: @Composable () -> Unit = {},
) {
    val backdropActive = LocalBackdropBlurActive.current
    val backdrop = rememberAppBackdrop(MiuixTheme.colorScheme.surface, backdropActive)
    CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
        val scrollBehavior = MiuixScrollBehavior()
        val title = page.title
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                val topBarModifier = Modifier.appBarBackdropBlur(
                    backdrop = backdrop,
                    surfaceColor = MiuixTheme.colorScheme.surface,
                    active = backdropActive,
                )
                val topBarColor = if (backdropActive) Color.Transparent else MiuixTheme.colorScheme.surface
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    modifier = topBarModifier,
                    color = topBarColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") } },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().appBackdropContent(backdrop, backdropActive).verticalScroll(scrollState)) {
                    ShellPageContent(page, padding, compactHome = false, pageContent = pageContent)
                }
                ShellFeedback(padding, content = feedbackContent)
            }
        }
    }
}

/** Top-bar actions of the headphones tab: overflow menu while connected, add-device button otherwise. */
@Composable
private fun MiuixHeadphoneActions(
    connectedHome: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onAddDevice: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Box {
        if (connectedHome) {
            IconButton(onClick = { onMenuExpandedChange(true) }) {
                Icon(RoseIcons[RoseIcon.MORE], "耳机菜单")
            }
        } else {
            IconButton(onClick = onAddDevice) {
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
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onDisconnect: () -> Unit = {},
    platformActions: @Composable (AppPage) -> Unit = {},
    animatePageChanges: Boolean = true,
) {
    LaunchedEffect(pagerState.pagerState.currentPage) {
        pagerState.syncPage()
    }
    val backdropActive = LocalBackdropBlurActive.current
    val floatingBackdrop = rememberAppBackdrop(
        MiuixTheme.colorScheme.surface,
        backdropActive && !wide && bottomBarStyle == BottomBarStyle.FLOATING,
    )
    val currentTab = mainPages.getOrElse(pagerState.selectedPage) { AppPage.HEADPHONES }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!wide) {
                if (bottomBarStyle == BottomBarStyle.FLOATING) {
                    FloatingNavigationBar(
                        modifier = Modifier.appPopupBackdropBlur(
                            backdrop = floatingBackdrop,
                            shape = CircleShape,
                            surfaceColor = MiuixTheme.colorScheme.surfaceContainer,
                            active = backdropActive,
                        ),
                        color = Color.Transparent,
                    ) {
                        mainPages.forEachIndexed { index, destination ->
                            FloatingNavigationBarItem(
                                selected = currentTab == destination,
                                onClick = {
                                    pagerState.selectPage(index, animate = animatePageChanges)
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
                                    pagerState.selectPage(index, animate = animatePageChanges)
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
        val trailingBottomPadding = if (isFloating) outerPadding.calculateBottomPadding() else 0.dp
        val pagerModifier = Modifier
            .fillMaxSize()
            .appBackdropContent(floatingBackdrop, backdropActive)
            .padding(bottom = pagerBottomPadding)
        val pageContent: @Composable PagerScope.(Int) -> Unit = { pageIndex ->
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
                        platformActions(page)
                        if (page == AppPage.HEADPHONES) {
                            MiuixHeadphoneActions(
                                connectedHome = connectedHome,
                                menuExpanded = menuExpanded,
                                onMenuExpandedChange = onMenuExpandedChange,
                                onAddDevice = { onNavigate(AppPage.ADD_DEVICE) },
                                onDisconnect = onDisconnect,
                            )
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
                                    bottomClearance = trailingBottomPadding,
                                ) {
                                    pageContentFor(page)
                                }
                            }
                            ShellFeedback(pagePadding, bottomClearance = trailingBottomPadding) {
                                pageFeedbackFor(page)
                            }
                        }
                    }
                }
            }
        }
        // Wide pages sit beside the vertical rail, so they travel vertically and only on rail taps.
        if (wide) {
            VerticalPager(
                state = pagerState.pagerState,
                modifier = pagerModifier,
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
                pageNestedScrollConnection = WideMainPagerNestedScrollConnection,
                key = { index -> mainPages[index] },
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState.pagerState,
                modifier = pagerModifier.pagerGestureOverride(pagerState = pagerState.pagerState),
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
                pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                key = { index -> mainPages[index] },
                pageContent = pageContent,
            )
        }
    }
}
