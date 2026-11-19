package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.dohex.openrose.ui.model.OpenRoseModel

/** The host's [OpenRoseModel.notice] as a card at the top of the page, until the user dismisses it. */
@Composable
internal fun NoticeBanner(model: OpenRoseModel) {
    val notice by model.notice.collectAsState()
    notice?.let { text ->
        AppDashboardCard {
            AppText(text)
            AppButton("知道了", { model.notice.value = null }, kind = AppButtonKind.Secondary)
        }
    }
}
