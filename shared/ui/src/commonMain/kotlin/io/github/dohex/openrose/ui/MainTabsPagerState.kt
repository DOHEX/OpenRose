package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.springAnimateToPage

internal class MainTabsPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun selectPage(targetPage: Int, animate: Boolean = true) {
        if (targetPage == selectedPage) return

        navJob?.cancel()
        selectedPage = targetPage

        if (!animate) {
            coroutineScope.launch {
                pagerState.scrollToPage(targetPage)
            }
            return
        }

        isNavigating = true
        navJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(targetPage)
            } finally {
                if (navJob == currentJob) {
                    isNavigating = false
                    selectedPage = pagerState.currentPage
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }

    fun isPageVisible(index: Int): Boolean = index == selectedPage || pagerState.layoutInfo.visiblePagesInfo.any { it.index == index }

    private val scrollToTopRequests = mutableStateMapOf<Int, Int>()

    fun requestScrollToTop(index: Int) {
        scrollToTopRequests[index] = scrollToTopRequest(index) + 1
    }

    fun scrollToTopRequest(index: Int): Int = scrollToTopRequests[index] ?: 0
}

internal val WideMainPagerNestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {}

@Composable
internal fun rememberMainTabsPagerState(initialPage: Int = 0): MainTabsPagerState {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { mainPages.size })
    val coroutineScope = rememberCoroutineScope()
    return remember(pagerState, coroutineScope) {
        MainTabsPagerState(pagerState, coroutineScope)
    }
}

@Composable
internal fun ScrollToTopOnRequest(request: Int, scrollState: ScrollState, expandTopBar: () -> Unit) {
    val currentExpandTopBar by rememberUpdatedState(expandTopBar)
    var handled by remember { mutableIntStateOf(request) }
    LaunchedEffect(request) {
        if (request != handled) {
            handled = request
            currentExpandTopBar()
            scrollState.animateScrollTo(0)
        }
    }
}

@Composable
internal fun rememberPageVisible(pagerState: MainTabsPagerState, index: Int): Boolean {
    val visible by remember(pagerState, index) { derivedStateOf { pagerState.isPageVisible(index) } }
    return visible
}

internal fun Modifier.mainPageSemantics(selected: Boolean): Modifier = if (selected) {
    fillMaxSize()
} else {
    fillMaxSize().clearAndSetSemantics { }.focusProperties {
        canFocus = false
        onEnter = { cancelFocusChange() }
    }
}
