package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.ffi.EnumNames
import uniffi.openrose_control.ControlError as RustControlError
import uniffi.openrose_control.ControlErrorCode as RustControlErrorCode
import uniffi.openrose_headset.HeadsetField as RustHeadsetField
import uniffi.openrose_headset.HeadsetSetting as RustHeadsetSetting
import uniffi.openrose_headset.ModelProfile as RustModelProfile

private val modelProfiles = EnumNames(ModelProfile.entries)
private val deviceFields = EnumNames(DeviceField.entries)
private val deviceSettings = EnumNames(DeviceSetting.entries)
private val errorCodes = EnumNames(ControlErrorCode.entries)

internal fun RustModelProfile.toKotlin(): ModelProfile = modelProfiles.of(this)

internal fun RustHeadsetField.toKotlin(): DeviceField = deviceFields.of(this)

internal fun RustHeadsetSetting.toKotlin(): DeviceSetting = deviceSettings.of(this)

internal fun RustControlErrorCode.toKotlin(): ControlErrorCode = errorCodes.of(this)

internal fun RustControlError.toKotlin(): ControlError = ControlError(code.toKotlin(), message)
