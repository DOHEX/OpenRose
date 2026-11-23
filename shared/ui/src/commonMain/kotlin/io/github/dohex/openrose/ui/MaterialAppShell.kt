@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package io.github.dohex.openrose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.ui.shell.ShellPageContent
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.textureBlur

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

/**
 * Top bar colors. With backdrop blur both container colors stay clear so only the blur tint shows:
 * a collapsing bar lerps container toward scrolled color, and Transparent (clear black) into an opaque
 * scrolled color passes through translucent grey, darkening the bar mid-collapse.
 */
@Composable
internal fun materialTopAppBarColors(backdropActive: Boolean): TopAppBarColors = if (backdropActive) {
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
} else {
    TopAppBarDefaults.topAppBarColors()
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
                label = { Text(destination.navigationLabel) },
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
    feedbackContent: @Composable (Modifier) -> Unit = {},
) {
    val backdrop = rememberAppBackdrop(MaterialTheme.colorScheme.surface, LocalBackdropBlurActive.current)
    CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        val title = page.title
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    modifier = Modifier.topBarBackdropBlur(backdrop, MaterialTheme.colorScheme.surface),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") } },
                    scrollBehavior = scrollBehavior,
                    colors = materialTopAppBarColors(backdrop != null),
                )
            },
        ) { padding ->
            ShellPageContent(
                page = page,
                padding = padding,
                scrollModifier = Modifier.appBackdropContent(backdrop).verticalScroll(scrollState),
                pageContent = pageContent,
                feedbackContent = feedbackContent,
            )
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
    pageFeedbackFor: @Composable (AppPage, Modifier) -> Unit = { _, _ -> },
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
    val floating = !wide && bottomBarStyle == BottomBarStyle.FLOATING
    // The bottom bar, fixed or floating, blurs the pages behind it, so they run under it.
    val barBackdrop = rememberAppBackdrop(MaterialTheme.colorScheme.surface, backdropActive && !wide)
    val contentBehindBar = floating || barBackdrop != null
    val currentTab = mainPages[pagerState.selectedPage]
    val headphoneMenu = HeadphoneMenuState(
        connectedHome = connectedHome,
        menuExpanded = menuExpanded,
        onMenuExpandedChange = onMenuExpandedChange,
        onAddDevice = { onNavigate(AppPage.ADD_DEVICE) },
        onDisconnect = onDisconnect,
    )
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!wide) MaterialBottomBar(floating, currentTab, barBackdrop, onNavigate)
        },
    ) { outerPadding ->
        val barHeight = outerPadding.calculateBottomPadding()
        val pagerModifier = Modifier
            .fillMaxSize()
            .appBackdropContent(barBackdrop)
            .padding(bottom = if (contentBehindBar) 0.dp else barHeight)
            .consumeWindowInsets(PaddingValues(bottom = barHeight))
        MaterialMainPager(pagerState, wide, pagerModifier) { pageIndex ->
            val page = mainPages[pageIndex]
            MaterialMainPage(
                pagerState = pagerState,
                pageIndex = pageIndex,
                backdropActive = backdropActive,
                scrollState = scrollStates.getValue(page),
                headphoneMenu = headphoneMenu,
                platformActions = platformActions,
                bottomClearance = if (contentBehindBar) barHeight else 0.dp,
                animateWidth = animatePageChanges,
                pageContent = { pageContentFor(page) },
                feedbackContent = { modifier -> pageFeedbackFor(page, modifier) },
            )
        }
    }
}

/** The narrow-screen bar: the floating toolbar or the fixed navigation bar, each blurring [barBackdrop] or solid without it. */
@Composable
private fun MaterialBottomBar(floating: Boolean, currentTab: AppPage, barBackdrop: Backdrop?, onNavigate: (AppPage) -> Unit) {
    if (floating) {
        MaterialFloatingTabs(currentTab, barBackdrop, onNavigate)
        return
    }
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    NavigationBar(
        modifier = Modifier.bottomBarBackdropBlur(barBackdrop, barColor),
        containerColor = if (barBackdrop != null) Color.Transparent else barColor,
    ) {
        mainPages.forEach { destination ->
            NavigationBarItem(
                selected = currentTab == destination,
                onClick = { onNavigate(destination) },
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

/** Wide pages sit beside the vertical rail, so they travel vertically and only on rail taps. */
@Composable
private fun MaterialMainPager(
    pagerState: MainTabsPagerState,
    wide: Boolean,
    modifier: Modifier,
    pageContent: @Composable PagerScope.(Int) -> Unit,
) {
    if (wide) {
        VerticalPager(
            state = pagerState.pagerState,
            modifier = modifier,
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            pageNestedScrollConnection = WideMainPagerNestedScrollConnection,
            key = { index -> mainPages[index] },
            pageContent = pageContent,
        )
    } else {
        HorizontalPager(
            state = pagerState.pagerState,
            modifier = modifier,
            beyondViewportPageCount = 1,
            key = { index -> mainPages[index] },
            pageContent = pageContent,
        )
    }
}

/** One main tab: its own collapsing top bar over its scrolling content. */
@Composable
private fun MaterialMainPage(
    pagerState: MainTabsPagerState,
    pageIndex: Int,
    backdropActive: Boolean,
    scrollState: ScrollState,
    headphoneMenu: HeadphoneMenuState,
    platformActions: @Composable (AppPage) -> Unit,
    bottomClearance: Dp,
    animateWidth: Boolean,
    pageContent: @Composable () -> Unit,
    feedbackContent: @Composable (Modifier) -> Unit,
) {
    val page = mainPages[pageIndex]
    val surface = MaterialTheme.colorScheme.surface
    // Pages kept composed beside the visible ones neither record nor blur a backdrop.
    val pageBackdrop = rememberAppBackdrop(surface, backdropActive && rememberPageVisible(pagerState, pageIndex))
    CompositionLocalProvider(LocalAppBackdrop provides pageBackdrop) {
        Box(Modifier.mainPageSemantics(pageIndex == pagerState.selectedPage)) {
            val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            ScrollToTopOnRequest(pagerState.scrollToTopRequest(pageIndex), scrollState) {
                scrollBehavior.state.heightOffset = 0f
            }
            Scaffold(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    LargeTopAppBar(
                        title = { Text(page.title) },
                        modifier = Modifier.topBarBackdropBlur(pageBackdrop, surface),
                        actions = { MainTabActions(page, platformActions, headphoneMenu) },
                        scrollBehavior = scrollBehavior,
                        colors = materialTopAppBarColors(pageBackdrop != null),
                    )
                },
            ) { pagePadding ->
                ShellPageContent(
                    page = page,
                    padding = pagePadding,
                    scrollModifier = Modifier.appBackdropContent(pageBackdrop).verticalScroll(scrollState),
                    compactHome = page == AppPage.HEADPHONES && !headphoneMenu.connectedHome,
                    bottomClearance = bottomClearance,
                    animateWidth = animateWidth,
                    pageContent = pageContent,
                    feedbackContent = feedbackContent,
                )
            }
        }
    }
}

@Composable
private fun MaterialFloatingTabs(
    currentTab: AppPage,
    barBackdrop: Backdrop?,
    onSelect: (AppPage) -> Unit,
) {
    val blurred = barBackdrop != null
    val reduceMotion = rememberReduceMotion()
    val spatialSpec = if (reduceMotion) snap<Dp>() else MaterialTheme.motionScheme.defaultSpatialSpec()
    val effectsSpec = if (reduceMotion) snap<Color>() else MaterialTheme.motionScheme.defaultEffectsSpec()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The toolbar's default container matches surfaceContainer list rows, so it vanishes
        // over them: lift it one tone (surfaceContainerHigh). Opaque, it gets MD3 floating
        // elevation (Level3, 6dp). Translucent over the blur, an elevation shadow fills the
        // whole shape and shows through the tint, darkening it, so the blurred toolbar uses
        // an outlineVariant hairline for separation instead.
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = if (barBackdrop != null) {
                Modifier
                    .textureBlur(
                        backdrop = barBackdrop,
                        shape = FloatingToolbarDefaults.ContainerShape,
                        blurRadius = BlurTokens.RADIUS,
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = FloatingToolbarDefaults.ContainerShape,
                    )
            } else {
                Modifier
            },
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.let {
                    if (blurred) it.copy(alpha = BlurTokens.MATERIAL_FLOATING_TINT) else it
                },
            ),
            expandedShadowElevation = if (blurred) 0.dp else 6.dp,
        ) {
            Row(Modifier.selectableGroup()) {
                mainPages.forEach { destination ->
                    val isSelected = currentTab == destination
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (isSelected) 56.dp else 48.dp,
                        animationSpec = spatialSpec,
                        label = "tabIndicatorWidth",
                    )
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f)
                        },
                        animationSpec = effectsSpec,
                        label = "tabContainerColor",
                    )
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = effectsSpec,
                        label = "tabIconTint",
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(containerColor)
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = { onSelect(destination) },
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
    }
}
