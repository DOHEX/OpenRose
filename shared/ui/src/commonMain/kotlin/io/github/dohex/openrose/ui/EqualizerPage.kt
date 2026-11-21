package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.confirmedCurve
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.writableCustomCurve
import io.github.dohex.openrose.core.writablePresets

/** The headset supplies available preset names; the application orders them for display. */
internal fun DeviceState.writableEqualizerPresets(): List<String> = writablePresets.sortedWith(presetDisplayOrder)

private val presetDisplayOrder = compareBy<String>(::equalizerPresetOrder, { it })

private fun equalizerPresetOrder(preset: String): Int = when (preset) {
    "pop" -> 0
    "hifi" -> 1
    "rock" -> 2
    "light" -> 3
    "eap" -> 4
    else -> 5
}

/**
 * The home 均衡器 entry, showing the confirmed mode only. A model with a writable curve opens the equalizer page; a
 * model with presets only picks one here, written at once and reported by the home setting feedback.
 */
@Composable
internal fun EqualizerEntryRow(
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
    onOpen: () -> Unit,
) {
    val presets = device.writableEqualizerPresets()
    if (device.writableCustomCurve != null) {
        AppArrowRow(title = "均衡器", value = confirmedChoiceValue(DeviceField.EQ, device), icon = RoseIcon.EQUALIZER, onClick = onOpen)
    } else if (presets.isNotEmpty()) {
        AppChoiceRow(
            title = "均衡器",
            valueText = confirmedChoiceValue(DeviceField.EQ, device),
            options = device.presetOptions(presets),
            selected = device.confirmedText(DeviceField.EQ),
            enabled = state.isReady(device) && !state.hasPendingSetting(device),
            icon = RoseIcon.EQUALIZER,
            onSelect = { onSet(HeadsetCommand.EqualizerPreset(it)) },
        )
    }
}

/**
 * Mode choice and, while the shown mode is custom, the custom curve; a model without presets has only the curve. The
 * page renders what the device can do now, so a curve that stops being writable takes its card with it.
 */
@Composable
internal fun EqualizerPage(state: ControlState, device: DeviceState, editor: EqualizerEditor) {
    val enabled = state.isReady(device)
    val presets = device.writableEqualizerPresets()
    val custom = device.writableCustomCurve
    val mode = editor.displayedMode(device.confirmedText(DeviceField.EQ))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (presets.isNotEmpty()) EqualizerModes(device, presets, custom, mode, enabled, editor)
        if (custom != null && (presets.isEmpty() || mode == CUSTOM_EQUALIZER_MODE)) CustomCurveCard(device, custom, mode, enabled, editor)
    }
}

/** The presets in order, then custom when the model has a curve; choosing custom writes the curve the page shows. */
@Composable
private fun EqualizerModes(
    device: DeviceState,
    presets: List<String>,
    custom: CustomEqualizerCapabilities?,
    mode: String?,
    enabled: Boolean,
    editor: EqualizerEditor,
) {
    AppGroup(title = "模式") {
        AppRadioGroup(
            options = device.presetOptions(presets) +
                listOfNotNull(custom?.let { AppOption(CUSTOM_EQUALIZER_MODE, "自定义") }),
            selected = mode,
            enabled = enabled,
            onSelect = { choice ->
                when {
                    choice == mode -> Unit

                    choice == CUSTOM_EQUALIZER_MODE && custom != null ->
                        editor.choose(EqualizerChoice.Curve(editor.shownCurve(device, custom)))

                    else -> editor.choose(EqualizerChoice.Preset(choice))
                }
            },
        )
    }
}

private fun DeviceState.presetOptions(presets: List<String>) = presets.map { AppOption(it, equalizerLabel(it, profile)) }

@Composable
private fun CustomCurveCard(
    device: DeviceState,
    custom: CustomEqualizerCapabilities,
    mode: String?,
    enabled: Boolean,
    editor: EqualizerEditor,
) {
    val curve = editor.shownCurve(device, custom)
    val flat = custom.flatCurve()
    AppGroup(
        title = "自定义曲线",
        titleAction = AppTitleAction("重置", enabled = enabled && curve != flat) { editor.choose(EqualizerChoice.Curve(flat)) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (device.confirmedCurve(custom) == null) AppText("尚未读取耳机上的曲线，以下为平直曲线", role = AppTextRole.Summary)
            AppSyncIndicator(editor.pending != null)
            EqualizerBands(
                curve = curve,
                capabilities = custom,
                enabled = enabled,
                active = enabled && mode == CUSTOM_EQUALIZER_MODE,
                onCommit = { editor.choose(EqualizerChoice.Curve(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The curve to show, and to write on choosing custom: the pending one, else the device's, else a flat one. */
private fun EqualizerEditor.shownCurve(device: DeviceState, custom: CustomEqualizerCapabilities): List<Int> {
    val confirmed = device.confirmedCurve(custom)
    return displayedCurve(confirmed) ?: custom.flatCurve()
}

private fun CustomEqualizerCapabilities.flatCurve(): List<Int> = List(bandsHz.size) { 0.coerceIn(minGain, maxGain) }
