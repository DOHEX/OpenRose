@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

@Composable
internal fun MaterialAppShell(backStack: List<AppPage>, page: AppPage, wide: Boolean,
    onNavigate: (AppPage) -> Unit, onBack: () -> Unit, scrollState: ScrollState,
    pageContent: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        if (wide) NavigationRail(header = { AppIcon(RoseIcon.HEADPHONES, Modifier.padding(12.dp), contentDescription = "OpenRose") }) {
            mainPages.forEach { destination ->
                NavigationRailItem(selected = backStack.first() == destination && page != AppPage.DIAGNOSTICS,
                    onClick = { onNavigate(destination) }, icon = { AppIcon(destination.icon) }, label = { Text(destination.title) })
            }
            Spacer(Modifier.weight(1f))
            NavigationRailItem(selected = page == AppPage.DIAGNOSTICS, onClick = { onNavigate(AppPage.DIAGNOSTICS) },
                icon = { AppIcon(RoseIcon.LOG) }, label = { Text(AppPage.DIAGNOSTICS.title) })
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val title = if (page == AppPage.HEADPHONES && !wide) "OpenRose" else page.title
        val navigation: @Composable () -> Unit = {
            if (backStack.size > 1) IconButton(onClick = onBack) { Icon(RoseIcons.Back, "返回") }
        }
        val actions: @Composable RowScope.() -> Unit = {
            if (page == AppPage.HEADPHONES) IconButton(onClick = { onNavigate(AppPage.ADD_DEVICE) }) { Icon(RoseIcons.Plus, "添加耳机") }
        }
        Scaffold(modifier = Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                if (page in mainPages) LargeTopAppBar(title = { Text(title) }, navigationIcon = navigation, actions = actions, scrollBehavior = scrollBehavior)
                else TopAppBar(title = { Text(title) }, navigationIcon = navigation, actions = actions)
            },
            bottomBar = {
                if (!wide && page in mainPages) NavigationBar {
                    mainPages.forEach { destination -> NavigationBarItem(selected = page == destination,
                        onClick = { onNavigate(destination) }, icon = { AppIcon(destination.icon) }, label = { Text(destination.navigationLabel) }) }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                ShellPageContent(page, padding, pageContent)
            }
        }
    }
}

@Composable
internal fun ShellPageContent(page: AppPage, padding: PaddingValues, pageContent: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = if (page == AppPage.HEADPHONES) 1120.dp else 800.dp).fillMaxWidth().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pageContent()
        }
    }
}
