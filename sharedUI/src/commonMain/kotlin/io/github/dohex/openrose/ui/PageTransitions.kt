package io.github.dohex.openrose.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastRoundToInt
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

internal fun pageTransitionOf(transition: PageTransition): NavTransition = when (transition) {
    PageTransition.MIUIX_SPRING -> NavTransitions.MiuixDefault
    PageTransition.SLIDE -> SlideTransition
    PageTransition.FADE -> FadeTransition
    PageTransition.NONE -> NavTransitions.None
}

private val SlideTransition: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    dismissDirection = NavSwipeDirection.LeftToRight,
) { scope ->
    val width = scope.layoutSize.width.toFloat()
    val d = scope.relativeDepth
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (d <= 0f) {
        translationX = ((if (rtl) -1f else 1f) * (-d).coerceIn(0f, 1f) * width).fastRoundToInt().toFloat()
    } else {
        translationX = (if (rtl) 1f else -1f) * d.coerceIn(0f, 1f) * width * 0.25f
    }
}

private val FadeTransition: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    dismissDirection = NavSwipeDirection.None,
) { scope ->
    val d = scope.relativeDepth
    alpha = if (d <= 0f) {
        (1f + d).coerceIn(0f, 1f)
    } else {
        (1f - d).coerceIn(0f, 1f)
    }
}
