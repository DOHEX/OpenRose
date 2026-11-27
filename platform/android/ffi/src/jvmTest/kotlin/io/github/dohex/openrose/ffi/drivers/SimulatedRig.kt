package io.github.dohex.openrose.ffi.drivers

import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PermissionState
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.TransportAvailability
import uniffi.openrose_headset.ModelProfile

internal const val ADDRESS = SimulatedDrivers.DEFAULT_ADDRESS

internal fun rig(profile: ModelProfile = ModelProfile.I5_ZT) = SimulatedDrivers(profile, alias = "My i5")

internal fun SimulatedDrivers.platformState(): PlatformState = PlatformState(
    permission = PermissionState.GRANTED,
    scanPermission = PermissionState.GRANTED,
    transport = TransportAvailability.Available,
    hostPlatform = HostPlatform.ANDROID,
    controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
    userUnlocked = true,
)

internal fun SimulatedDrivers.timing() = fastTiming()

internal fun SimulatedDrivers.clock() = fixedClock()
