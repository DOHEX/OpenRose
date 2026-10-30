@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur

@Composable
internal fun MaterialAppShell(
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
    val backdrop = rememberAppBackdrop(MaterialTheme.colorScheme.surface, backdropActive)
    val menuFocusRequester = remember { FocusRequester() }
    CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
        Row(Modifier.fillMaxSize()) {
        if (wide) NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
            mainPages.forEach { destination ->
                val isSelected = if (backStack.isNotEmpty()) backStack.first() == destination else page == destination
                NavigationRailItem(selected = isSelected,
                    onClick = { onNavigate(destination) }, icon = { AppIcon(destination.icon) }, label = { Text(destination.title) })
            }
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val title = page.title
        val navigation: @Composable () -> Unit = {
            if (isSecondary) IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") }
        }
        val actions: @Composable RowScope.() -> Unit = {
            if (page == AppPage.HEADPHONES) {
                Box {
                    if (connectedHome) {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(RoseIcons[RoseIcon.MORE], contentDescription = "耳机菜单")
                        }
                    } else if (showAddDeviceAction) {
                        IconButton(onClick = { onNavigate(AppPage.ADD_DEVICE) }) {
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
        }
        Scaffold(modifier = Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                val topBarModifier = Modifier.appBarBackdropBlur(
                    backdrop = backdrop,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    active = backdropActive,
                )
                val topBarColors = if (backdropActive) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors()
                val largeTopBarColors = if (backdropActive) TopAppBarDefaults.largeTopAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.largeTopAppBarColors()
                if (page in mainPages) {
                    LargeTopAppBar(title = { Text(title) }, modifier = topBarModifier, navigationIcon = navigation, actions = actions, scrollBehavior = scrollBehavior, colors = largeTopBarColors)
                } else {
                    TopAppBar(title = { Text(title) }, modifier = topBarModifier, navigationIcon = navigation, actions = actions, colors = topBarColors)
                }
            },
            bottomBar = {
                if (showBottomBar) {
                    val bottomBarModifier = Modifier.appBarBackdropBlur(
                        backdrop = backdrop,
                        surfaceColor = MaterialTheme.colorScheme.surfaceContainer,
                        active = backdropActive,
                    )
                    val bottomBarColor = if (backdropActive) Color.Transparent else NavigationBarDefaults.containerColor
                    NavigationBar(modifier = bottomBarModifier, containerColor = bottomBarColor) {
                        mainPages.forEach { destination -> NavigationBarItem(selected = page == destination,
                            onClick = { onNavigate(destination) }, icon = { AppIcon(destination.icon) }, label = { Text(destination.navigationLabel) }) }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().appBackdropContent(backdrop, backdropActive).verticalScroll(scrollState)) {
                    ShellPageContent(page, padding, compactHome = page == AppPage.HEADPHONES && !connectedHome, pageContent)
                }
                ShellFeedback(padding, feedbackContent)
            }
        }
        }
    }
}

@Composable
internal fun BoxScope.ShellFeedback(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.align(Alignment.BottomCenter)
        .padding(bottom = padding.calculateBottomPadding())
        .widthIn(max = 600.dp).fillMaxWidth().padding(16.dp)) {
        content()
    }
}

@Composable
internal fun ShellPageContent(page: AppPage, padding: PaddingValues, compactHome: Boolean, pageContent: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        val maxWidth = if (compactHome) 680.dp else if (page == AppPage.HEADPHONES) 1120.dp else 800.dp
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pageContent()
        }
    }
}

@Composable
internal fun MaterialMainShell(
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
    val backdropActive = LocalBackdropBlurActive.current
    val currentTab = mainPages.getOrElse(pagerState.selectedPage) { AppPage.HEADPHONES }
    val menuFocusRequester = remember { FocusRequester() }
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
                        icon = { AppIcon(destination.icon) },
                        label = { Text(destination.title) },
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
                                icon = { AppIcon(destination.icon) },
                                label = { Text(destination.navigationLabel) },
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
                        if (page == AppPage.HEADPHONES && isSelected) {
                            Box {
                            if (connectedHome) {
                                IconButton(onClick = { onMenuExpandedChange(true) }) {
                                    Icon(RoseIcons[RoseIcon.MORE], contentDescription = "耳机菜单")
                                }
                            } else if (showAddDeviceAction) {
                                IconButton(onClick = { onNavigate(AppPage.ADD_DEVICE) }) {
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
}
