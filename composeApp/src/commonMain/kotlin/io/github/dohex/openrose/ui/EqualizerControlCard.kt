package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

@Composable
internal fun EqualizerControlCard(
    state: ControlState,
    device: DeviceState,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val presets = device.capabilities.equalizer?.presets.orEmpty()
    val hasEqualizer = DeviceSetting.EQ in device.capabilities.writableSettings && presets.isNotEmpty()
    val hasGameMode = DeviceSetting.GAME_MODE in device.capabilities.writableSettings
    if (!hasEqualizer && !hasGameMode) return

    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    val confirmedPreset = (device.knownValue(DeviceField.EQ) as? FieldValue.Text)?.value
    val footnote = if (hasEqualizer && device.profile == ModelProfile.I7_RS_V2 && "eap" !in presets) {
        "EAP 仅在设备固件达到型号要求后开放。"
    } else {
        null
    }
    AppGroup(title = "均衡器 / 音效", footnote = footnote) {
        if (hasEqualizer) {
            AppRadioGroup(
                options = presets
                    .sortedWith(compareBy({ equalizerPresetOrder(it) }, { it }))
                    .map { preset -> AppOption(preset, equalizerLabel(preset, device.profile)) },
                selected = confirmedPreset,
                enabled = enabled,
                onSelect = { preset ->
                    if (preset != confirmedPreset) onSet(DeviceSetting.EQ, FieldValue.Text(preset))
                },
            )
        }

        if (hasGameMode) {
            if (hasEqualizer) AppDivider()
            SettingToggleChoices("游戏模式", DeviceField.GAME_MODE, DeviceSetting.GAME_MODE, device, enabled, onSet)
        }
    }
}

private fun equalizerPresetOrder(preset: String): Int = when (preset) {
    "pop" -> 0
    "hifi" -> 1
    "rock" -> 2
    "light" -> 3
    "eap" -> 4
    else -> 5
}
