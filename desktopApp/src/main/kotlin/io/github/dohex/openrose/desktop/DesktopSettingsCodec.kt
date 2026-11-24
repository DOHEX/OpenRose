package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.ui.TrayPanelControl
import io.github.dohex.openrose.ui.TrayPanelSettings
import io.github.dohex.openrose.ui.desktop.DesktopSettings

internal object DesktopSettingsCodec {
    private const val BACKGROUND_CONTROL = "desktop.background_control"
    private const val TRAY_PANEL_NOISE_LEVELS = "desktop.tray_panel.noise_levels"
    private const val TRAY_PANEL_ENABLED = "desktop.tray_panel.enabled"
    private const val TRAY_PANEL_ORDER = "desktop.tray_panel.order"

    fun encode(settings: DesktopSettings): Map<String, String> {
        val trayPanel = settings.trayPanel.normalized()
        return mapOf(
            BACKGROUND_CONTROL to settings.backgroundControl.toString(),
            TRAY_PANEL_NOISE_LEVELS to trayPanel.showNoiseLevels.toString(),
            TRAY_PANEL_ENABLED to trayPanel.orderedEnabledControls.joinToString(",") { it.id },
            TRAY_PANEL_ORDER to trayPanel.controlOrder.joinToString(",") { it.id },
        )
    }

    fun decode(values: Map<String, String>): DesktopSettings {
        val defaults = DesktopSettings()
        val defaultTray = defaults.trayPanel
        val enabledRaw = values[TRAY_PANEL_ENABLED]
        val enabledControls = if (enabledRaw == null) {
            defaultTray.enabledControls
        } else {
            enabledRaw.split(",").mapNotNull { TrayPanelControl.fromId(it.trim()) }.toSet()
        }
        val orderRaw = values[TRAY_PANEL_ORDER]
        val controlOrder = if (orderRaw == null) {
            defaultTray.controlOrder
        } else {
            orderRaw.split(",").mapNotNull { TrayPanelControl.fromId(it.trim()) }
        }
        val trayPanel = TrayPanelSettings(
            showNoiseLevels = values[TRAY_PANEL_NOISE_LEVELS]?.toBooleanStrictOrNull() ?: defaultTray.showNoiseLevels,
            enabledControls = enabledControls,
            controlOrder = controlOrder,
        ).normalized()
        return DesktopSettings(
            backgroundControl = values[BACKGROUND_CONTROL]?.toBooleanStrictOrNull() ?: defaults.backgroundControl,
            trayPanel = trayPanel,
        )
    }
}
