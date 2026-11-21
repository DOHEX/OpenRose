package io.github.dohex.openrose.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
internal sealed interface Route : NavKey {
    @Serializable
    data class Main(val tab: AppPage = AppPage.HEADPHONES) : Route

    @Serializable
    data object Touch : Route

    @Serializable
    data object Equalizer : Route

    @Serializable
    data object Audio : Route

    @Serializable
    data object Details : Route

    @Serializable
    data object Diagnostics : Route

    @Serializable
    data object Personalization : Route

    @Serializable
    data object CirculationPairing : Route

    @Serializable
    data object About : Route

    @Serializable
    data object AddDevice : Route
}

internal fun Route.toAppPage(): AppPage = when (this) {
    is Route.Main -> tab
    else -> AppPage.entries.first { it.secondaryRoute == this }
}
