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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import io.github.dohex.openrose.ui.shell.ShellPageContent
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/**
 * Page scrolling with the Miuix bounce on every page. A scroll container's own overscroll only
 * engages while its content can scroll, so pages shorter than the viewport would never bounce;
 * [overScrollVertical] takes the leftover drag from the nested scroll chain instead.
 */
private fun Modifier.miuixPageScroll(state: ScrollState): Modifier = overScrollVertical().verticalScroll(state, overscrollEffect = null)

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
    feedbackContent: @Composable (Modifier) -> Unit = {},
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
            ShellPageContent(
                page = page,
                padding = padding,
                scrollModifier = Modifier.appBackdropContent(backdrop, backdropActive).miuixPageScroll(scrollState),
                pageContent = pageContent,
                feedbackContent = feedbackContent,
            )
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
    // Liquid glass needs the same shader support as blur; without it the capsule falls back to the solid frosted bar.
    val glassBackdrop = rememberLiquidGlassBackdrop(
        MiuixTheme.colorScheme.surface,
        active = floating && backdropActive && floatingBarMaterial == FloatingBarMaterial.LIQUID_GLASS,
    )
    val floatingBackdrop = rememberAppBackdrop(MiuixTheme.colorScheme.surface, backdropActive && floating && glassBackdrop == null)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!wide) {
                MiuixMainBottomBar(
                    selectedIndex = pagerState.selectedPage,
                    floating = floating,
                    floatingBackdrop = floatingBackdrop.takeIf { backdropActive },
                    glassBackdrop = glassBackdrop,
                    onSelect = { index ->
                        pagerState.selectPage(index, animate = animatePageChanges)
                        onNavigate(mainPages[index])
                    },
                )
            }
        },
    ) { outerPadding ->
        val pagerBottomPadding = if (floating) 0.dp else outerPadding.calculateBottomPadding()
        val trailingBottomPadding = if (floating) outerPadding.calculateBottomPadding() else 0.dp
        val pagerModifier = Modifier
            .fillMaxSize()
            .appBackdropContent(floatingBackdrop, backdropActive)
            .liquidGlassContent(glassBackdrop)
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
                    onEnter = { cancelFocusChange() }
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
                            TopAppBar(
                                title = title,
                                largeTitle = title,
                                modifier = topBarModifier,
                                color = topBarColor,
                                scrollBehavior = scrollBehavior,
                                actions = actions,
                            )
                        },
                    ) { pagePadding ->
                        ShellPageContent(
                            page = page,
                            padding = pagePadding,
                            scrollModifier = Modifier.appBackdropContent(pageBackdrop, backdropActive)
                                .miuixPageScroll(scrollStates.getValue(page)),
                            compactHome = page == AppPage.HEADPHONES && !connectedHome,
                            bottomClearance = trailingBottomPadding,
                            pageContent = { pageContentFor(page) },
                            feedbackContent = { modifier -> pageFeedbackFor(page, modifier) },
                        )
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

/**
 * The narrow-screen bar: liquid glass when [glassBackdrop] is given, otherwise the floating capsule (frosted over
 * [floatingBackdrop], solid without it) or the fixed bar. [onSelect] receives the index into [mainPages].
 */
@Composable
private fun MiuixMainBottomBar(
    selectedIndex: Int,
    floating: Boolean,
    floatingBackdrop: Backdrop?,
    glassBackdrop: LayerBackdrop?,
    onSelect: (Int) -> Unit,
) {
    if (glassBackdrop != null) {
        MiuixLiquidGlassBottomBar(backdrop = glassBackdrop, selectedIndex = selectedIndex, onSelect = onSelect)
    } else if (floating) {
        // Mirrors the Miuix example: the glass stroke highlight (or, without blur, the divider
        // outline) separates the bar from surfaceContainer cards scrolling underneath it.
        val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
        FloatingNavigationBar(
            modifier = if (floatingBackdrop != null) {
                Modifier.textureBlur(
                    backdrop = floatingBackdrop,
                    shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
                        ),
                    ),
                    highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
                )
            } else {
                Modifier
            },
            color = if (floatingBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            showDivider = floatingBackdrop == null,
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
        NavigationBar {
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
