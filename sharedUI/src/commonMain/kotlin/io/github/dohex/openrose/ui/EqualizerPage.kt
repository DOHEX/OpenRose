package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

/** The home row leading to the equalizer page; it shows the confirmed mode only, never a pending choice. */
@Composable
internal fun EqualizerEntryRow(device: DeviceState, onOpen: () -> Unit) {
    if (device.writableEqualizerPresets().isEmpty() && device.writableCustomEqualizer() == null) return
    AppArrowRow(
        title = "均衡器",
        value = confirmedChoiceValue(DeviceField.EQ, device),
        icon = RoseIcon.EQUALIZER,
        onClick = onOpen,
    )
}

@Composable
internal fun EqualizerPage(state: ControlState, device: DeviceState, editor: EqualizerEditor) {
    val enabled = state.isReady(device)
    val presets = device.writableEqualizerPresets()
    val custom = device.writableCustomEqualizer()
    val mode = editor.displayedMode((device.knownValue(DeviceField.EQ) as? FieldValue.Text)?.value)
    val confirmedCurve = custom?.let { device.confirmedCurve(it) }
    val curve = custom?.let { editor.displayedCurve(confirmedCurve) ?: it.flatCurve() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (presets.isNotEmpty()) {
            AppGroup(title = "预设") {
                val options = presets.map { AppOption(it, equalizerLabel(it, device.profile)) } +
                    listOfNotNull(custom?.let { AppOption(CUSTOM_EQUALIZER_MODE, "自定义") })
                AppChipGroup(
                    options = options,
                    selected = mode,
                    enabled = enabled,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    onSelect = { choice ->
                        if (choice == CUSTOM_EQUALIZER_MODE && curve != null) {
                            editor.choose(EqualizerChoice.Curve(curve))
                        } else {
                            editor.choose(EqualizerChoice.Preset(choice))
                        }
                    },
                )
            }
        }
        if (custom != null && curve != null) {
            val flat = custom.flatCurve()
            AppGroup(
                title = "自定义曲线",
                titleTrailing = {
                    AppButton(
                        "重置",
                        onClick = { editor.choose(EqualizerChoice.Curve(flat)) },
                        enabled = enabled && (curve != flat || mode != CUSTOM_EQUALIZER_MODE),
                        kind = AppButtonKind.Secondary,
                    )
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    curveStatus(mode, confirmedCurve != null, device)?.let { AppText(it, role = AppTextRole.Summary) }
                    AppSyncIndicator(editor.pending != null)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        EqualizerBands(
                            curve = curve,
                            capabilities = custom,
                            enabled = enabled,
                            active = enabled && mode == CUSTOM_EQUALIZER_MODE,
                            onCommit = { editor.choose(EqualizerChoice.Curve(it)) },
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun curveStatus(mode: String?, curveRead: Boolean, device: DeviceState): String? = when {
    !curveRead -> "尚未读取耳机上的曲线，以下为平直曲线"
    mode == CUSTOM_EQUALIZER_MODE -> "耳机正在使用这条曲线"
    mode != null -> "耳机正在使用预设「${equalizerLabel(mode, device.profile)}」，调整任一频段即切换为这条曲线"
    else -> null
}

private fun DeviceState.confirmedCurve(custom: CustomEqualizerCapabilities): List<Int>? {
    val values = (knownValue(DeviceField.CUSTOM_EQ) as? FieldValue.IntegerList)?.values ?: return null
    return values.toList().takeIf { it.size == custom.bandsHz.size && it.all { gain -> gain in custom.minGain..custom.maxGain } }
}

private fun CustomEqualizerCapabilities.flatCurve(): List<Int> = List(bandsHz.size) { 0.coerceIn(minGain, maxGain) }
