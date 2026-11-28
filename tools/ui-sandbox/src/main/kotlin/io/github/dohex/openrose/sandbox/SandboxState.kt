package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

private val batteryFields = listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)

internal fun sameSession(previous: SandboxScenario, next: SandboxScenario): Boolean = previous.link >= SandboxLink.CONNECTING &&
    next.link >= previous.link &&
    previous.profile == next.profile &&
    previous.firmware == next.firmware

internal fun ControlState.showing(scenario: SandboxScenario, sessionId: Long, keepReadings: Boolean): ControlState {
    val target = sandboxCandidate(scenario.profile)
    val kept = device?.diagnostics?.fields?.takeIf { keepReadings && scenario.link == SandboxLink.READY }
    val initialFacts = buildMap {
        if (scenario.link == SandboxLink.READY) {
            scenario.firmwareValue?.let { put(DeviceField.FIRMWARE, FieldReading.Known(it, "")) }
            put(DeviceField.GAME_MODE, FieldReading.Known(FieldValue.Toggle(false), ""))
        }
    }
    val capabilities = scenario.profile.capabilities(kept ?: initialFacts)
    val readings = deviceReadings(scenario, capabilities, kept)
    return copy(
        platform = sandboxPlatform(scenario),
        connection = sandboxConnection(scenario, target.deviceId, sessionId),
        bondedDevices = if (scenario.link == SandboxLink.FIRST_VISIT) persistentListOf() else persistentListOf(target),
        device = if (scenario.link >= SandboxLink.INITIALIZING) {
            DeviceState(target.deviceId, sessionId, scenario.profile, capabilities, readings, classicAddress = target.classicAddress)
        } else {
            null
        },
        connectionTarget = target.takeIf { scenario.link >= SandboxLink.CONNECTING },
    )
}

internal fun ControlState.profileOf(deviceId: DeviceId): ModelProfile? = (bondedDevices + candidates)
    .firstOrNull { it.deviceId == deviceId }?.profile

internal val sandboxScanResults: List<DeviceCandidate>
    get() = ModelProfile.entries.map(::sandboxCandidate) + DeviceCandidate(DeviceId("sandbox-other"), "其他蓝牙耳机", -71, null, "")

private fun sandboxCandidate(profile: ModelProfile) = DeviceCandidate(
    deviceId = DeviceId("sandbox-${profile.name.lowercase()}"),
    name = profile.displayName,
    rssi = -52,
    profile = profile,
    manufacturerDataHex = "",
    classicAddress = "11:22:33:44:55:66",
)

private fun sandboxConnection(scenario: SandboxScenario, deviceId: DeviceId, sessionId: Long): ConnectionState = when (scenario.link) {
    SandboxLink.FIRST_VISIT, SandboxLink.PAIRED -> ConnectionState.Disconnected
    SandboxLink.CONNECTING -> ConnectionState.Connecting(deviceId, sessionId, ConnectionPhase.CONNECTING)
    SandboxLink.INITIALIZING -> ConnectionState.Connecting(deviceId, sessionId, ConnectionPhase.INITIALIZING)
    SandboxLink.READY -> ConnectionState.Connected(deviceId, sessionId, scenario.profile)
}

private fun deviceReadings(
    scenario: SandboxScenario,
    capabilities: DeviceCapabilities,
    kept: PersistentMap<DeviceField, FieldReading>?,
): PersistentMap<DeviceField, FieldReading> = when {
    scenario.link != SandboxLink.READY -> persistentMapOf()

    kept != null -> kept.puttingAll(
        batteryFields.filter { it in capabilities.readableFields }
            .associateWith { batteryReading(it, scenario.profile.batteryLayout, scenario.battery) },
    )

    else -> sandboxFields(scenario.profile, capabilities, scenario.firmwareValue, scenario.battery)
}

private fun sandboxPlatform(scenario: SandboxScenario): PlatformState {
    val issue = scenario.issue
    return PlatformState(
        permission = when {
            issue == SandboxPlatformIssue.PERMISSION_DENIED -> PermissionState.DENIED
            scenario.host == HostPlatform.ANDROID -> PermissionState.GRANTED
            else -> PermissionState.NOT_REQUIRED
        },
        transport = when (issue) {
            SandboxPlatformIssue.NONE, SandboxPlatformIssue.PERMISSION_DENIED -> TransportAvailability.Available
            SandboxPlatformIssue.BLUETOOTH_OFF -> TransportAvailability.Disabled
            SandboxPlatformIssue.TRANSPORT_UNAVAILABLE -> unavailableTransport(scenario)
        },
        hostPlatform = scenario.host,
        controlTransportApplicability = if (scenario.host == HostPlatform.UNKNOWN) {
            PlatformApplicability.NOT_APPLICABLE
        } else {
            PlatformApplicability.APPLICABLE
        },
    )
}

private fun unavailableTransport(scenario: SandboxScenario): TransportAvailability.Unavailable = when (scenario.host) {
    HostPlatform.ANDROID -> TransportAvailability.Unavailable(TransportUnavailableReason.BleUnsupported)
    HostPlatform.LINUX -> TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable("系统总线不可用"))
    HostPlatform.WINDOWS -> TransportAvailability.Unavailable(TransportUnavailableReason.BleUnsupported)
    HostPlatform.UNKNOWN -> TransportAvailability.Unavailable(TransportUnavailableReason.UnsupportedPlatform(scenario.platformName))
}
