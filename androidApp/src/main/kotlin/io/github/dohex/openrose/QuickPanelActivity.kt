package io.github.dohex.openrose

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.PrimaryDeviceSelection
import io.github.dohex.openrose.ui.QuickPanel
import io.github.dohex.openrose.ui.model.WindowDestination

private val panelStatusBarStyle = SystemBarStyle.dark(0x99000000.toInt())

class QuickPanelActivity : ComponentActivity() {
    private val owner get() = application as OpenRoseApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = panelStatusBarStyle)
        setContent {
            val uiSettings by owner.model.uiSettings.collectAsState()
            val integration by owner.runtime.integration.collectAsState()
            val dark = when (uiSettings.appearance) {
                Appearance.SYSTEM ->
                    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES

                Appearance.LIGHT -> false

                Appearance.DARK -> true
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = panelStatusBarStyle,
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }
            CirculationVisibility {
                QuickPanel(
                    model = owner.model,
                    selection = remember { PrimaryDeviceSelection() },
                    dynamicColor = integration.dynamicColorInEffect,
                    onOpenApp = ::openApp,
                    onDismiss = ::finish,
                    circulation = owner.circulation.takeIf { integration.circulationAvailable },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        owner.refreshPlatform()
        owner.background.startIfEnabled()
    }

    private fun openApp(destination: WindowDestination?) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_PANEL_DESTINATION, (destination ?: WindowDestination.HEADPHONES).name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
