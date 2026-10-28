@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

@Composable
internal fun MaterialAppShell(backStack: List<AppPage>, page: AppPage, wide: Boolean,
    onNavigate: (AppPage) -> Unit, onBack: () -> Unit, scrollState: ScrollState,
    pageContent: @Composable () -> Unit,
    connectedHome: Boolean = false,
    showAddDeviceAction: Boolean = true,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    val menuFocusRequester = remember { FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        if (wide) NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
            mainPages.forEach { destination ->
                NavigationRailItem(selected = backStack.first() == destination,
                    onClick = { onNavigate(destination) }, icon = { AppIcon(destination.icon) }, label = { Text(destination.title) })
            }
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val title = if (page == AppPage.HEADPHONES && !wide) "OpenRose" else page.title
        val navigation: @Composable () -> Unit = {
            if (backStack.size > 1) IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") }
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
                if (page in mainPages && !(page == AppPage.HEADPHONES && !connectedHome)) LargeTopAppBar(title = { Text(title) }, navigationIcon = navigation, actions = actions, scrollBehavior = scrollBehavior)
                else TopAppBar(title = { Text(title) }, navigationIcon = navigation, actions = actions)
            },
            bottomBar = {
                if (!wide && page in mainPages) NavigationBar {
                    mainPages.forEach { destination -> NavigationBarItem(selected = page == destination,
                        onClick = { onNavigate(destination) }, icon = { AppIcon(destination.icon) }, label = { Text(destination.navigationLabel) }) }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                ShellPageContent(page, padding, compactHome = page == AppPage.HEADPHONES && !connectedHome, pageContent)
            }
        }
    }
}

@Composable
internal fun ShellPageContent(page: AppPage, padding: PaddingValues, compactHome: Boolean, pageContent: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        val maxWidth = if (compactHome) 600.dp else if (page == AppPage.HEADPHONES) 1120.dp else 800.dp
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth()
            .padding(horizontal = if (compactHome) 16.dp else 0.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pageContent()
        }
    }
}
