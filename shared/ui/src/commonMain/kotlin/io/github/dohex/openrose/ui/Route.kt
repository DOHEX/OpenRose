package io.github.dohex.openrose.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
internal sealed interface Route : NavKey {
    val page: AppPage

    @Serializable
    data class Main(val tab: AppPage = AppPage.HEADPHONES) : Route {
        override val page: AppPage get() = tab
    }

    @Serializable
    data class Secondary(override val page: AppPage) : Route {
        init {
            require(!page.isMainTab) { "$page is a main tab, not a pushed page" }
        }
    }
}
