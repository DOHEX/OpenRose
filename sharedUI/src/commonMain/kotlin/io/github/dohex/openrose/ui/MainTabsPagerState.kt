package io.github.dohex.openrose.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import io.github.dohex.openrose.core.PageTransition
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
import kotlin.math.abs

/**
 * [selectedPage] flips the instant a tab is chosen, while [pagerState] still animates towards it, so the
 * two disagree during every transition. Use [selectedPage] only for navigation chrome (bar highlight,
 * back handling) and for semantics/focus isolation. NEVER gate visible page content (top-bar actions,
 * feedback) on it: that content would vanish while its page is still sliding on screen.
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

    fun selectPage(targetPage: Int, transition: PageTransition = PageTransition.MIUIX_SPRING) {
        if (targetPage == selectedPage) return

        navJob?.cancel()
        selectedPage = targetPage

        if (transition == PageTransition.NONE) {
            coroutineScope.launch {
                pagerState.scrollToPage(targetPage)
            }
            return
        }

        isNavigating = true
        val duration = 100 * abs(targetPage - pagerState.currentPage).coerceAtLeast(2) + 100

        navJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                if (transition == PageTransition.MIUIX_SPRING) {
                    pagerState.springAnimateToPage(targetPage)
                } else {
                    pagerState.animateScrollToPage(
                        page = targetPage,
                        animationSpec = tween(easing = EaseInOut, durationMillis = duration),
                    )
                }
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
