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
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

private val batteryFields = listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)

/** The same model and firmware staying connected keep one session; anything else is a new one. */
internal fun sameSession(previous: SandboxScenario?, next: SandboxScenario): Boolean = previous != null &&
    previous.link >= SandboxLink.CONNECTING &&
    next.link >= SandboxLink.CONNECTING &&
    previous.profile == next.profile &&
    previous.firmware == next.firmware

/**
 * This state rebuilt for [scenario]: the platform, connection and device follow it, while settings, scans and
 * operations stay. With [keepReadings] a ready device keeps the readings its confirmed writes left and only takes the
 * scenario's battery.
 */
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

/** The model of a bonded or discovered device, for the app's own connect requests. */
internal fun ControlState.profileOf(deviceId: DeviceId): ModelProfile? = (bondedDevices + candidates)
    .firstOrNull { it.deviceId == deviceId }?.profile

/** What a scan finds: one device of every model, and one that is not a ROSE device. */
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
    SandboxLink.INITIALIZING -> ConnectionState.Connected(deviceId, sessionId, scenario.profile, ConnectionPhase.INITIALIZING)
    SandboxLink.READY -> ConnectionState.Connected(deviceId, sessionId, scenario.profile, ConnectionPhase.READY)
}

/** An initializing device has read nothing yet. */
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

/** The platform facts each host reports, with the scenario's issue applied the way that host would show it. */
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
            SandboxPlatformIssue.TRANSPORT_UNAVAILABLE -> TransportAvailability.Unavailable(unavailableReason(scenario))
        },
        hostPlatform = scenario.host,
        controlTransportApplicability = if (scenario.host == HostPlatform.UNKNOWN) {
            PlatformApplicability.NOT_APPLICABLE
        } else {
            PlatformApplicability.APPLICABLE
        },
    )
}

private fun unavailableReason(scenario: SandboxScenario): String = when (scenario.host) {
    HostPlatform.ANDROID -> "此设备不支持低功耗蓝牙"
    HostPlatform.LINUX -> "无法连接 BlueZ：系统总线不可用"
    HostPlatform.WINDOWS -> "Windows 蓝牙适配器不支持 BLE"
    HostPlatform.UNKNOWN -> "${scenario.platformName} 控制传输尚未实现"
}
