package io.github.dohex.openrose.ui

import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

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
}

/** Wide shells page vertically beside the rail and only on rail taps; page content keeps every scroll and fling. */
internal val WideMainPagerNestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {}

@Composable
internal fun rememberMainTabsPagerState(
    initialPage: Int = 0,
    pageCount: () -> Int = { mainPages.size },
): MainTabsPagerState {
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = pageCount,
    )
    val coroutineScope = rememberCoroutineScope()
    return remember(pagerState, coroutineScope) {
        MainTabsPagerState(pagerState, coroutineScope)
    }
}
