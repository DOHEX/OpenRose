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
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue

/** The custom curve the model lets OpenRose write, or null when it has none it can write. */
internal fun DeviceState.writableCustomEqualizer(): CustomEqualizerCapabilities? {
    if (DeviceSetting.CUSTOM_EQ !in capabilities.writableSettings) return null
    return capabilities.equalizer?.custom?.takeIf { it.bandsHz.isNotEmpty() && it.minGain <= it.maxGain }
}

/** Writable presets in display order; the custom slot is offered by the curve editor instead, when there is one. */
internal fun DeviceState.writableEqualizerPresets(): List<String> {
    if (DeviceSetting.EQ !in capabilities.writableSettings) return emptyList()
    val hasCurve = writableCustomEqualizer() != null
    return capabilities.equalizer?.presets.orEmpty()
        .filterNot { hasCurve && it == CUSTOM_EQUALIZER_MODE }
        .sortedWith(compareBy({ equalizerPresetOrder(it) }, { it }))
}

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
    onSet: (DeviceSetting, FieldValue) -> Unit,
    onOpen: () -> Unit,
) {
    val presets = device.writableEqualizerPresets()
    if (device.writableCustomEqualizer() != null) {
        AppArrowRow(title = "均衡器", value = confirmedChoiceValue(DeviceField.EQ, device), icon = RoseIcon.EQUALIZER, onClick = onOpen)
    } else if (presets.isNotEmpty()) {
        AppChoiceRow(
            title = "均衡器",
            valueText = confirmedChoiceValue(DeviceField.EQ, device),
            options = device.presetOptions(presets),
            selected = device.confirmedEqualizerMode(),
            enabled = state.isReady(device) && !state.hasPendingSetting(device),
            icon = RoseIcon.EQUALIZER,
            onSelect = { onSet(DeviceSetting.EQ, FieldValue.Text(it)) },
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
    val custom = device.writableCustomEqualizer()
    val mode = editor.displayedMode(device.confirmedEqualizerMode())
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

private fun DeviceState.confirmedEqualizerMode(): String? = (knownValue(DeviceField.EQ) as? FieldValue.Text)?.value

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

private fun DeviceState.confirmedCurve(custom: CustomEqualizerCapabilities): List<Int>? {
    val values = (knownValue(DeviceField.CUSTOM_EQ) as? FieldValue.IntegerList)?.values ?: return null
    return values.toList().takeIf { it.size == custom.bandsHz.size && it.all { gain -> gain in custom.minGain..custom.maxGain } }
}

private fun CustomEqualizerCapabilities.flatCurve(): List<Int> = List(bandsHz.size) { 0.coerceIn(minGain, maxGain) }
