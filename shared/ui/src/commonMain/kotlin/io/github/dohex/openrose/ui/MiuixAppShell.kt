@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import io.github.dohex.openrose.ui.shell.ShellPageContent
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

private fun Modifier.miuixPageScroll(state: ScrollState): Modifier = overScrollVertical().verticalScroll(state, overscrollEffect = null)

@Composable
internal fun MiuixNavigationRail(selected: AppPage, onSelect: (AppPage) -> Unit) {
    NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
        mainPages.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = RoseIcons[destination.icon],
                label = destination.navigationLabel,
            )
        }
    }
}

@Composable
internal fun MiuixAppShell(
    page: AppPage,
    onBack: () -> Unit,
    scrollState: ScrollState,
    pageContent: @Composable () -> Unit,
    feedbackContent: @Composable (Modifier) -> Unit = {},
) {
    val backdrop = rememberAppBackdrop(MiuixTheme.colorScheme.surface, LocalBackdropBlurActive.current)
    CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
        val scrollBehavior = MiuixScrollBehavior()
        val title = page.title
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    modifier = Modifier.topBarBackdropBlur(backdrop, MiuixTheme.colorScheme.surface),
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") } },
                )
            },
        ) { padding ->
            ShellPageContent(
                page = page,
                padding = padding,
                scrollModifier = Modifier.appBackdropContent(backdrop).miuixPageScroll(scrollState),
                pageContent = pageContent,
                feedbackContent = feedbackContent,
            )
        }
    }
}

@Composable
internal fun MiuixMainShell(
    pagerState: MainTabsPagerState,
    wide: Boolean,
    bottomBarStyle: BottomBarStyle = BottomBarStyle.FIXED,
    floatingBarMaterial: FloatingBarMaterial = FloatingBarMaterial.FROSTED,
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
    val glassBackdrop = rememberLiquidGlassBackdrop(
        MiuixTheme.colorScheme.surface,
        active = floating && backdropActive && floatingBarMaterial == FloatingBarMaterial.LIQUID_GLASS,
    )
    val barBackdrop = rememberAppBackdrop(MiuixTheme.colorScheme.surface, backdropActive && !wide && glassBackdrop == null)
    val contentBehindBar = floating || barBackdrop != null
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
            if (!wide) {
                MiuixMainBottomBar(
                    selectedIndex = pagerState.selectedPage,
                    floating = floating,
                    barBackdrop = barBackdrop,
                    glassBackdrop = glassBackdrop,
                    onSelect = { index -> onNavigate(mainPages[index]) },
                )
            }
        },
    ) { outerPadding ->
        val barHeight = outerPadding.calculateBottomPadding()
        val pagerModifier = Modifier
            .fillMaxSize()
            .appBackdropContent(barBackdrop)
            .liquidGlassContent(glassBackdrop)
            .padding(bottom = if (contentBehindBar) 0.dp else barHeight)
            .consumeWindowInsets(PaddingValues(bottom = barHeight))
        MiuixMainPager(pagerState, wide, pagerModifier) { pageIndex ->
            val page = mainPages[pageIndex]
            MiuixMainPage(
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

@Composable
private fun MiuixMainPager(
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
            modifier = modifier.pagerGestureOverride(pagerState = pagerState.pagerState),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            pageNestedScrollConnection = PagerGestureNestedScrollConnection,
            key = { index -> mainPages[index] },
            pageContent = pageContent,
        )
    }
}

@Composable
private fun MiuixMainPage(
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
    val surface = MiuixTheme.colorScheme.surface
    val pageBackdrop = rememberAppBackdrop(surface, backdropActive && rememberPageVisible(pagerState, pageIndex))
    CompositionLocalProvider(LocalAppBackdrop provides pageBackdrop) {
        Box(Modifier.mainPageSemantics(pageIndex == pagerState.selectedPage)) {
            val scrollBehavior = MiuixScrollBehavior()
            ScrollToTopOnRequest(pagerState.scrollToTopRequest(pageIndex), scrollState) {
                scrollBehavior.state.heightOffset = 0f
            }
            Scaffold(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    TopAppBar(
                        title = page.title,
                        largeTitle = page.title,
                        modifier = Modifier.topBarBackdropBlur(pageBackdrop, surface),
                        color = if (pageBackdrop != null) Color.Transparent else surface,
                        scrollBehavior = scrollBehavior,
                        actions = { MainTabActions(page, platformActions, headphoneMenu) },
                    )
                },
            ) { pagePadding ->
                ShellPageContent(
                    page = page,
                    padding = pagePadding,
                    scrollModifier = Modifier.appBackdropContent(pageBackdrop).miuixPageScroll(scrollState),
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
private fun MiuixMainBottomBar(
    selectedIndex: Int,
    floating: Boolean,
    barBackdrop: Backdrop?,
    glassBackdrop: LayerBackdrop?,
    onSelect: (Int) -> Unit,
) {
    if (glassBackdrop != null) {
        MiuixLiquidGlassBottomBar(backdrop = glassBackdrop, selectedIndex = selectedIndex, onSelect = onSelect)
    } else if (floating) {
        val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
        val tint = MiuixTheme.colorScheme.surfaceContainer
        FloatingNavigationBar(
            modifier = if (barBackdrop != null) {
                Modifier.textureBlur(
                    backdrop = barBackdrop,
                    shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                    blurRadius = BlurTokens.RADIUS,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(color = tint.copy(alpha = BlurTokens.MIUIX_FLOATING_TINT))),
                    ),
                    highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
                )
            } else {
                Modifier
            },
            color = if (barBackdrop != null) Color.Transparent else tint,
            showDivider = barBackdrop == null,
        ) {
            mainPages.forEachIndexed { index, destination ->
                FloatingNavigationBarItem(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    icon = RoseIcons[destination.icon],
                    label = destination.navigationLabel,
                )
            }
        }
    } else {
        val surface = MiuixTheme.colorScheme.surface
        NavigationBar(
            modifier = Modifier.bottomBarBackdropBlur(barBackdrop, surface),
            color = if (barBackdrop != null) Color.Transparent else surface,
            showDivider = barBackdrop == null,
        ) {
            mainPages.forEachIndexed { index, destination ->
                NavigationBarItem(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    icon = RoseIcons[destination.icon],
                    label = destination.navigationLabel,
                )
            }
        }
    }
}
