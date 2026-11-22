package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.FieldValue

// What a row shows while a write to its setting is pending: the confirmed value stays selected and the requested
// value, read here, gets the progress indicator.

/** The value being written to [setting] when it is a [T]. */
internal inline fun <reified T : FieldValue> ControlState.pendingValue(
    device: DeviceState,
    setting: DeviceSetting,
): T? = pendingSetting(device, setting)?.requestedValue as? T

internal fun ControlState.pendingText(
    device: DeviceState,
    setting: DeviceSetting,
): String? = pendingValue<FieldValue.Text>(device, setting)?.value

internal fun ControlState.pendingNumber(
    device: DeviceState,
    setting: DeviceSetting,
): Int? = pendingValue<FieldValue.Number>(device, setting)?.value

internal fun ControlState.pendingToggle(
    device: DeviceState,
    setting: DeviceSetting,
): Boolean? = pendingValue<FieldValue.Toggle>(device, setting)?.value
