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

/**
 * [selectedPage] flips the instant a tab is chosen, while [pagerState] still animates towards it, so the
 * two disagree during every transition. Use [selectedPage] only for navigation chrome (bar highlight,
 * back handling) and for semantics/focus isolation. NEVER gate visible page content (top-bar actions,
 * feedback) on it: that content would vanish while its page is still sliding on screen.
 *
 * Main pages always switch with the Miuix spring; the 转场动效 preference only governs secondary routes,
 * except that 关闭动效 also makes main pages jump ([selectPage] with `animate = false`).
 */
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

    /**
     * Whether the page at [index] is on screen, including while it slides in or out. Off-screen pages kept
     * composed by the pager need not record a blur backdrop. The selected page counts before the first layout.
     */
    fun isPageVisible(index: Int): Boolean = index == selectedPage || pagerState.layoutInfo.visiblePagesInfo.any { it.index == index }

    private val scrollToTopRequests = mutableStateMapOf<Int, Int>()

    /** Re-tapping the current tab asks its page to scroll back to the top; the page owns its scroll state and top bar. */
    fun requestScrollToTop(index: Int) {
        scrollToTopRequests[index] = scrollToTopRequest(index) + 1
    }

    /** Counts the requests for the page at [index]; a change means scroll to top. */
    fun scrollToTopRequest(index: Int): Int = scrollToTopRequests[index] ?: 0
}

/** Wide shells page vertically beside the rail and only on rail taps; page content keeps every scroll and fling. */
internal val WideMainPagerNestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {}

@Composable
internal fun rememberMainTabsPagerState(initialPage: Int = 0): MainTabsPagerState {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { mainPages.size })
    val coroutineScope = rememberCoroutineScope()
    return remember(pagerState, coroutineScope) {
        MainTabsPagerState(pagerState, coroutineScope)
    }
}

/** Scrolls a page to the top, with its collapsed top bar expanded by [expandTopBar], whenever [request] changes while composed. */
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

/** Whether the page at [index] is on screen; read as state, so scrolling the pager does not recompose the page. */
@Composable
internal fun rememberPageVisible(pagerState: MainTabsPagerState, index: Int): Boolean {
    val visible by remember(pagerState, index) { derivedStateOf { pagerState.isPageVisible(index) } }
    return visible
}

/** Pages the pager keeps composed beside the selected one are hidden from semantics and keep no focus. */
internal fun Modifier.mainPageSemantics(selected: Boolean): Modifier = if (selected) {
    fillMaxSize()
} else {
    fillMaxSize().clearAndSetSemantics { }.focusProperties {
        canFocus = false
        onEnter = { cancelFocusChange() }
    }
}
