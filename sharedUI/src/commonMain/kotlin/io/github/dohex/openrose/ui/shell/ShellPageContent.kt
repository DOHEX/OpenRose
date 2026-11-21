package io.github.dohex.openrose.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.ui.AppPage

/** Feedback owns the space it occupies, so scrolling to the last row leaves its full touch target exposed. */
@Composable
internal fun ShellPageContent(
    page: AppPage,
    padding: PaddingValues,
    scrollModifier: Modifier,
    compactHome: Boolean = false,
    bottomClearance: Dp = 0.dp,
    pageContent: @Composable () -> Unit,
    feedbackContent: @Composable (Modifier) -> Unit,
) {
    val pageWidth = when {
        compactHome -> 680.dp
        page == AppPage.HEADPHONES -> 1120.dp
        else -> 800.dp
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxFeedbackHeight = maxHeight / 2
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).fillMaxWidth().then(scrollModifier).padding(padding).consumeWindowInsets(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = pageWidth).fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pageContent()
                    if (bottomClearance > 0.dp) {
                        Spacer(Modifier.height(bottomClearance).testTag("ShellBottomClearance"))
                    }
                }
            }
            feedbackContent(
                Modifier
                    .padding(bottom = padding.calculateBottomPadding() + bottomClearance)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxFeedbackHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}
