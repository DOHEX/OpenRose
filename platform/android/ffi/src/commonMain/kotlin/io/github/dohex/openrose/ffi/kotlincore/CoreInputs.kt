package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.ffi.EnumNames
import uniffi.openrose_control.ControlSettings
import uniffi.openrose_control.HostPlatform as RustHostPlatform
import uniffi.openrose_control.PermissionState as RustPermissionState
import uniffi.openrose_control.PlatformApplicability as RustPlatformApplicability
import uniffi.openrose_control.PlatformState as RustPlatformState
import uniffi.openrose_control.TransportAvailability as RustTransportAvailability
import uniffi.openrose_control.TransportUnavailableReason as RustUnavailableReason

private val rustHostPlatforms = EnumNames(RustHostPlatform.entries)
private val rustPermissionStates = EnumNames(RustPermissionState.entries)
private val rustApplicabilities = EnumNames(RustPlatformApplicability.entries)

fun PlatformState.toRust(): RustPlatformState = RustPlatformState(
    permission = permission.toRust(),
    transport = transport.toRust(),
    hostPlatform = rustHostPlatforms.of(hostPlatform),
    userUnlocked = userUnlocked,
    scanPermission = scanPermission.toRust(),
    controlTransportApplicability = rustApplicabilities.of(controlTransportApplicability),
)

private fun TransportAvailability.toRust(): RustTransportAvailability = when (this) {
    TransportAvailability.Available -> RustTransportAvailability.Available
    TransportAvailability.Disabled -> RustTransportAvailability.Disabled
    is TransportAvailability.Unavailable -> RustTransportAvailability.Unavailable(reason.toRust())
}

private fun TransportUnavailableReason.toRust(): RustUnavailableReason = when (this) {
    is TransportUnavailableReason.UnsupportedPlatform -> RustUnavailableReason.UnsupportedPlatform(platform)
    is TransportUnavailableReason.MissingDependency -> RustUnavailableReason.MissingDependency(detail)
    is TransportUnavailableReason.BackendUnavailable -> RustUnavailableReason.BackendUnavailable(detail)
    else -> toFixedRustReason()
}

private fun TransportUnavailableReason.toFixedRustReason(): RustUnavailableReason = when (this) {
    TransportUnavailableReason.BleUnsupported -> RustUnavailableReason.BleUnsupported

    TransportUnavailableReason.PermissionRequired -> RustUnavailableReason.PermissionRequired

    TransportUnavailableReason.NoAdapter -> RustUnavailableReason.NoAdapter

    TransportUnavailableReason.AdapterPoweredOff -> RustUnavailableReason.AdapterPoweredOff

    TransportUnavailableReason.AdapterStateUnknown -> RustUnavailableReason.AdapterStateUnknown

    TransportUnavailableReason.AccessDenied -> RustUnavailableReason.AccessDenied

    TransportUnavailableReason.ServiceUnavailable -> RustUnavailableReason.ServiceUnavailable

    TransportUnavailableReason.Initializing -> RustUnavailableReason.Initializing

    TransportUnavailableReason.Recovering -> RustUnavailableReason.Recovering

    TransportUnavailableReason.ShuttingDown -> RustUnavailableReason.ShuttingDown

    TransportUnavailableReason.Closed -> RustUnavailableReason.Closed

    is TransportUnavailableReason.UnsupportedPlatform,
    is TransportUnavailableReason.MissingDependency,
    is TransportUnavailableReason.BackendUnavailable,
    -> error("handled by toRust")
}

fun UserSettings.toRust(): ControlSettings = ControlSettings(forceBle = forceBle)

internal fun PermissionState.toRust(): RustPermissionState = rustPermissionStates.of(this)

internal fun PlatformApplicability.toRust(): RustPlatformApplicability = rustApplicabilities.of(this)
