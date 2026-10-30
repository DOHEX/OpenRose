package io.github.dohex.openrose.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
internal sealed interface Route : NavKey {
    @Serializable
    data class Main(val tab: AppPage = AppPage.HEADPHONES) : Route

    @Serializable
    data object Equalizer : Route

    @Serializable
    data object Touch : Route

    @Serializable
    data object Audio : Route

    @Serializable
    data object Details : Route

    @Serializable
    data object Diagnostics : Route

    @Serializable
    data object AddDevice : Route
}

internal fun AppPage.toRoute(): Route = when (this) {
    AppPage.HEADPHONES -> Route.Main(AppPage.HEADPHONES)
    AppPage.SYSTEM -> Route.Main(AppPage.SYSTEM)
    AppPage.SETTINGS -> Route.Main(AppPage.SETTINGS)
    AppPage.EQUALIZER -> Route.Equalizer
    AppPage.TOUCH -> Route.Touch
    AppPage.AUDIO -> Route.Audio
    AppPage.DETAILS -> Route.Details
    AppPage.DIAGNOSTICS -> Route.Diagnostics
    AppPage.ADD_DEVICE -> Route.AddDevice
}

internal fun Route.toAppPage(): AppPage = when (this) {
    is Route.Main -> tab
    Route.Equalizer -> AppPage.EQUALIZER
    Route.Touch -> AppPage.TOUCH
    Route.Audio -> AppPage.AUDIO
    Route.Details -> AppPage.DETAILS
    Route.Diagnostics -> AppPage.DIAGNOSTICS
    Route.AddDevice -> AppPage.ADD_DEVICE
}
