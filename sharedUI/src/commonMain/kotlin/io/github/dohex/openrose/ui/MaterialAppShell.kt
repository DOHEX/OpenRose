@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package io.github.dohex.openrose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
private fun MaterialRailHeader() {
    Box(
        modifier = Modifier
            .padding(top = 8.dp, bottom = 16.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(RoseIcon.HEADPHONES, contentDescription = "OpenRose")
    }
}

/** The wide-screen rail. The app root places it beside the route stack so page transitions never move it. */
@Composable
internal fun MaterialNavigationRail(selected: AppPage, onSelect: (AppPage) -> Unit) {
    NavigationRail(
        header = { MaterialRailHeader() },
    ) {
        mainPages.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { AppIcon(destination.icon) },
                label = { Text(destination.title) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** Renders a pushed (secondary) page; main tabs live in [MaterialMainShell]. */
@Composable
internal fun MaterialAppShell(
    page: AppPage,
    onBack: () -> Unit,
    scrollState: ScrollState,
    pageContent: @Composable () -> Unit,
    feedbackContent: @Composable () -> Unit = {},
) {
    val backdropActive = LocalBackdropBlurActive.current
    val backdrop = rememberAppBackdrop(MaterialTheme.colorScheme.surface, backdropActive)
    CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
        val title = page.title
        Scaffold(modifier = Modifier.fillMaxSize(),
            topBar = {
                val topBarModifier = Modifier.appBarBackdropBlur(
                    backdrop = backdrop,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    active = backdropActive,
                )
                val topBarColors = if (backdropActive) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors()
                TopAppBar(
                    title = { Text(title) },
                    modifier = topBarModifier,
                    navigationIcon = { IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") } },
                    colors = topBarColors,
                )
            }) { padding ->
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
private fun MaterialHeadphoneActions(
    connectedHome: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onAddDevice: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val menuFocusRequester = remember { FocusRequester() }
    Box {
        if (connectedHome) {
            IconButton(onClick = { onMenuExpandedChange(true) }) {
                Icon(RoseIcons[RoseIcon.MORE], contentDescription = "耳机菜单")
            }
        } else {
            IconButton(onClick = onAddDevice) {
                Icon(RoseIcons.Plus, contentDescription = "添加耳机")
            }
        }
        DropdownMenu(
            expanded = connectedHome && menuExpanded,
            onDismissRequest = { onMenuExpandedChange(false) },
        ) {
            LaunchedEffect(menuExpanded) {
                if (menuExpanded) menuFocusRequester.requestFocus()
            }
            DropdownMenuItem(
                modifier = Modifier.focusRequester(menuFocusRequester).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        onMenuExpandedChange(false)
                        true
                    } else {
                        false
                    }
                },
                text = { Text("断开控制连接") },
                onClick = {
                    onMenuExpandedChange(false)
                    onDisconnect()
                },
            )
        }
    }
}

@Composable
internal fun BoxScope.ShellFeedback(
    padding: PaddingValues,
    bottomClearance: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.align(Alignment.BottomCenter)
        .padding(bottom = padding.calculateBottomPadding() + bottomClearance)
        .widthIn(max = 600.dp).fillMaxWidth().padding(16.dp)) {
        content()
    }
}

@Composable
internal fun ShellPageContent(
    page: AppPage,
    padding: PaddingValues,
    compactHome: Boolean,
    bottomClearance: Dp = 0.dp,
    pageContent: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        val maxWidth = if (compactHome) 680.dp else if (page == AppPage.HEADPHONES) 1120.dp else 800.dp
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pageContent()
            if (bottomClearance > 0.dp) {
                Spacer(
                    Modifier
                        .height(bottomClearance)
                        .clearAndSetSemantics { contentDescription = "ShellBottomClearance" },
                )
            }
        }
    }
}

@Composable
internal fun MaterialMainShell(
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
        MaterialTheme.colorScheme.surface,
        backdropActive && !wide && bottomBarStyle == BottomBarStyle.FLOATING,
    )
    val currentTab = mainPages.getOrElse(pagerState.selectedPage) { AppPage.HEADPHONES }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!wide) {
                    if (bottomBarStyle == BottomBarStyle.FLOATING) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            HorizontalFloatingToolbar(
                                expanded = true,
                                modifier = Modifier.appPopupBackdropBlur(
                                    backdrop = floatingBackdrop,
                                    shape = CircleShape,
                                    surfaceColor = MaterialTheme.colorScheme.surfaceContainer,
                                    active = backdropActive,
                                ),
                                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                                    toolbarContainerColor = Color.Transparent,
                                ),
                            ) {
                                mainPages.forEachIndexed { index, destination ->
                                    val isSelected = currentTab == destination
                                    val indicatorWidth by animateDpAsState(
                                        targetValue = if (isSelected) 56.dp else 48.dp,
                                        animationSpec = spring(
                                            dampingRatio = 0.75f,
                                            stiffness = 400f,
                                        ),
                                        label = "tabIndicatorWidth",
                                    )
                                    val containerColor by animateColorAsState(
                                        targetValue = if (isSelected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f)
                                        },
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                        label = "tabContainerColor",
                                    )
                                    val iconTint by animateColorAsState(
                                        targetValue = if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                        label = "tabIconTint",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(containerColor)
                                            .selectable(
                                                selected = isSelected,
                                                role = Role.Tab,
                                                onClick = {
                                                    pagerState.selectPage(index, animate = animatePageChanges)
                                                    onNavigate(destination)
                                                },
                                            )
                                            .size(width = indicatorWidth, height = 48.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppIcon(
                                            destination.icon,
                                            contentDescription = destination.navigationLabel,
                                            tint = iconTint,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val bottomBarColor = if (backdropActive) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer
                        NavigationBar(containerColor = bottomBarColor) {
                            mainPages.forEachIndexed { index, destination ->
                                NavigationBarItem(
                                    selected = currentTab == destination,
                                    onClick = {
                                        pagerState.selectPage(index, animate = animatePageChanges)
                                        onNavigate(destination)
                                    },
                                    icon = { AppIcon(destination.icon) },
                                    label = { Text(destination.navigationLabel) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
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
                val pageBackdrop = rememberAppBackdrop(MaterialTheme.colorScheme.surface, backdropActive)
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
                    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                    val title = page.title
                    val actions: @Composable RowScope.() -> Unit = {
                        platformActions(page)
                        if (page == AppPage.HEADPHONES) {
                            MaterialHeadphoneActions(
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
                            surfaceColor = MaterialTheme.colorScheme.surface,
                            active = backdropActive,
                        )
                        val topBarColors = if (backdropActive) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors()
                        val largeTopBarColors = if (backdropActive) TopAppBarDefaults.largeTopAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.largeTopAppBarColors()
                        if (page in mainPages) {
                            LargeTopAppBar(title = { Text(title) }, modifier = topBarModifier, actions = actions, scrollBehavior = scrollBehavior, colors = largeTopBarColors)
                        } else {
                            TopAppBar(title = { Text(title) }, modifier = topBarModifier, actions = actions, colors = topBarColors)
                        }
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
                    modifier = pagerModifier,
                    beyondViewportPageCount = 1,
                    key = { index -> mainPages[index] },
                    pageContent = pageContent,
                )
            }
        }
}
