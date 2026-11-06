package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Scaffold

/** Page and section tests keep the component style's surface and popup host without app navigation. */
@Composable
internal fun TestPageContent(
    style: InterfaceStyle,
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    ProvideInterfaceStyle(style, dark) {
        when (style) {
            InterfaceStyle.MIUIX -> Scaffold(Modifier.fillMaxSize()) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }

            InterfaceStyle.MATERIAL3 -> Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}
