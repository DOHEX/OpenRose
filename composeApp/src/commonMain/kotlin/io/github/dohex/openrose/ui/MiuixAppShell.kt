package io.github.dohex.openrose.ui

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
internal fun MiuixAppShell(backStack: List<AppPage>, page: AppPage, wide: Boolean,
    onNavigate: (AppPage) -> Unit, onBack: () -> Unit, scrollState: ScrollState,
    pageContent: @Composable () -> Unit,
    connectedHome: Boolean = false,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    Row(Modifier.fillMaxSize()) {
        if (wide) NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
            mainPages.forEach { destination ->
                NavigationRailItem(selected = backStack.first() == destination,
                    onClick = { onNavigate(destination) }, icon = RoseIcons[destination.icon], label = destination.title)
            }
        }
        val scrollBehavior = MiuixScrollBehavior()
        val title = if (page == AppPage.HEADPHONES && !wide) "OpenRose" else page.title
        val navigation: @Composable () -> Unit = {
            if (backStack.size > 1) IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") }
        }
        val actions: @Composable RowScope.() -> Unit = {
            if (page == AppPage.HEADPHONES) {
                Box {
                    if (connectedHome) {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(RoseIcons[RoseIcon.MORE], "耳机菜单")
                        }
                    } else {
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
                if (!wide && page in mainPages) NavigationBar {
                    mainPages.forEach { destination -> NavigationBarItem(selected = page == destination,
                        onClick = { onNavigate(destination) }, icon = RoseIcons[destination.icon], label = destination.navigationLabel) }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                ShellPageContent(page, padding, compactHome = page == AppPage.HEADPHONES && !connectedHome, pageContent)
            }
        }
    }
}
