package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.dohex.openrose.ui.quickTileSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The quick settings tile of the 快捷面板. It only mirrors the control state while the tile is visible and never
 * holds a control session; a tap always opens [QuickPanelActivity], connected or not.
 */
class QuickPanelTileService : TileService() {
    private val owner get() = application as OpenRoseApplication
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listening
        listening.launch {
            owner.model.state.collect { state ->
                val summary = quickTileSummary(state)
                qsTile?.apply {
                    this.state = if (summary.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = "OpenRose"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = summary.subtitle
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openPanel) else openPanel()
    }

    // The Intent overload is only reached below Android 14, where it is the supported call.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openPanel() {
        val intent = Intent(this, QuickPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
