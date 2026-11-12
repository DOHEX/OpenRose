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
    data object About : Route

    @Serializable
    data object AddDevice : Route
}

internal fun AppPage.toRoute(): Route = when (this) {
    AppPage.HEADPHONES -> Route.Main(AppPage.HEADPHONES)
    AppPage.STATUS -> Route.Main(AppPage.STATUS)
    AppPage.SETTINGS -> Route.Main(AppPage.SETTINGS)
    AppPage.TOUCH -> Route.Touch
    AppPage.EQUALIZER -> Route.Equalizer
    AppPage.AUDIO -> Route.Audio
    AppPage.DETAILS -> Route.Details
    AppPage.DIAGNOSTICS -> Route.Diagnostics
    AppPage.PERSONALIZATION -> Route.Personalization
    AppPage.ABOUT -> Route.About
    AppPage.ADD_DEVICE -> Route.AddDevice
}

internal fun Route.toAppPage(): AppPage = when (this) {
    is Route.Main -> tab
    Route.Touch -> AppPage.TOUCH
    Route.Equalizer -> AppPage.EQUALIZER
    Route.Audio -> AppPage.AUDIO
    Route.Details -> AppPage.DETAILS
    Route.Diagnostics -> AppPage.DIAGNOSTICS
    Route.Personalization -> AppPage.PERSONALIZATION
    Route.About -> AppPage.ABOUT
    Route.AddDevice -> AppPage.ADD_DEVICE
}
