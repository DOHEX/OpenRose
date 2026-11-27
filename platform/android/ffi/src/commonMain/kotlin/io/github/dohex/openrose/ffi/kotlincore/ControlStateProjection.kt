package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionRecovery
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import uniffi.openrose_control.ConnectionRecovery as RustConnectionRecovery
import uniffi.openrose_control.ConnectionState as RustConnectionState
import uniffi.openrose_control.ControlSnapshot as RustSnapshot
import uniffi.openrose_control.HeadsetCandidate as RustCandidate
import uniffi.openrose_control.PlatformState as RustPlatformState
import uniffi.openrose_control.ScanState as RustScanState
import uniffi.openrose_control.SettingOperation as RustSettingOperation
import uniffi.openrose_control.TransportAvailability as RustTransportAvailability
import uniffi.openrose_control.TransportEndpoint as RustTransportEndpoint
import uniffi.openrose_control.TransportSelection as RustTransportSelection
import uniffi.openrose_control.TransportUnavailableReason as RustUnavailableReason

private val permissionStates = EnumNames(PermissionState.entries)
private val hostPlatforms = EnumNames(HostPlatform.entries)
private val applicabilities = EnumNames(PlatformApplicability.entries)
private val connectionPhases = EnumNames(ConnectionPhase.entries)
private val settingStages = EnumNames(SettingStage.entries)
private val recoveryReasons = EnumNames(TransportRecoveryReason.entries)
private val selectionReasons = EnumNames(TransportSelectionReason.entries)

internal fun RustSnapshot.toControlState(): ControlState = ControlState(
    platform = platform.toKotlin(),
    connection = connection.toKotlin(),
    settings = UserSettings(forceBle = settings.forceBle),
    scan = scan.toKotlin(),
    candidates = candidates.map { it.toKotlin() }.toPersistentList(),
    bondedDevices = bondedHeadsets.map { it.toKotlin() }.toPersistentList(),
    device = headset?.toKotlin(),
    lastError = lastError?.toKotlin(),
    settingOperations = settingOperations.map { it.toKotlin() }.toPersistentList(),
    transportSelection = headset?.transportSelection?.toKotlin(),
    connectionRecovery = connectionRecovery?.toKotlin(),
    connectionTarget = connectionTarget?.toKotlin(),
    audioDeviceId = audioHeadsetId?.let(::DeviceId),
    selectedHeadsetId = selectedHeadsetId?.let(::DeviceId),
)

internal fun RustPlatformState.toKotlin(): PlatformState = PlatformState(
    permission = permissionStates.of(permission),
    transport = transport.toKotlin(),
    hostPlatform = hostPlatforms.of(hostPlatform),
    controlTransportApplicability = applicabilities.of(controlTransportApplicability),
    userUnlocked = userUnlocked,
    scanPermission = permissionStates.of(scanPermission),
)

private fun RustTransportAvailability.toKotlin(): TransportAvailability = when (this) {
    RustTransportAvailability.Available -> TransportAvailability.Available
    RustTransportAvailability.Disabled -> TransportAvailability.Disabled
    is RustTransportAvailability.Unavailable -> TransportAvailability.Unavailable(reason.toKotlin())
}

private fun RustUnavailableReason.toKotlin(): TransportUnavailableReason = when (this) {
    is RustUnavailableReason.UnsupportedPlatform -> TransportUnavailableReason.UnsupportedPlatform(platform)
    is RustUnavailableReason.MissingDependency -> TransportUnavailableReason.MissingDependency(detail)
    is RustUnavailableReason.BackendUnavailable -> TransportUnavailableReason.BackendUnavailable(detail)
    else -> toFixedKotlinReason()
}

private fun RustUnavailableReason.toFixedKotlinReason(): TransportUnavailableReason = when (this) {
    RustUnavailableReason.BleUnsupported -> TransportUnavailableReason.BleUnsupported

    RustUnavailableReason.PermissionRequired -> TransportUnavailableReason.PermissionRequired

    RustUnavailableReason.NoAdapter -> TransportUnavailableReason.NoAdapter

    RustUnavailableReason.AdapterPoweredOff -> TransportUnavailableReason.AdapterPoweredOff

    RustUnavailableReason.AdapterStateUnknown -> TransportUnavailableReason.AdapterStateUnknown

    RustUnavailableReason.AccessDenied -> TransportUnavailableReason.AccessDenied

    RustUnavailableReason.ServiceUnavailable -> TransportUnavailableReason.ServiceUnavailable

    RustUnavailableReason.Initializing -> TransportUnavailableReason.Initializing

    RustUnavailableReason.Recovering -> TransportUnavailableReason.Recovering

    RustUnavailableReason.ShuttingDown -> TransportUnavailableReason.ShuttingDown

    RustUnavailableReason.Closed -> TransportUnavailableReason.Closed

    is RustUnavailableReason.UnsupportedPlatform,
    is RustUnavailableReason.MissingDependency,
    is RustUnavailableReason.BackendUnavailable,
    -> error("handled by toKotlin")
}

private fun RustConnectionState.toKotlin(): ConnectionState = when (this) {
    RustConnectionState.Disconnected -> ConnectionState.Disconnected

    is RustConnectionState.Connecting ->
        ConnectionState.Connecting(DeviceId(headsetId), sessionId.toLong(), connectionPhases.of(phase))

    is RustConnectionState.Connected ->
        ConnectionState.Connected(DeviceId(headsetId), sessionId.toLong(), profile.toKotlin())
}

private fun RustScanState.toKotlin(): ScanState = when (this) {
    RustScanState.Idle -> ScanState.Idle
    RustScanState.Scanning -> ScanState.Scanning
    is RustScanState.Failed -> ScanState.Failed(v1.toKotlin())
}

private fun RustCandidate.toKotlin(): DeviceCandidate = DeviceCandidate(
    deviceId = DeviceId(id),
    name = name,
    rssi = rssi?.toInt(),
    profile = profile?.toKotlin(),
    manufacturerDataHex = manufacturerDataHex,
    classicAddress = classicAddress,
)

private fun RustConnectionRecovery.toKotlin(): ConnectionRecovery = ConnectionRecovery(sessionId.toLong(), recoveryReasons.of(reason))

private fun RustTransportSelection.toKotlin(): TransportSelection = TransportSelection(
    when (val selected = endpoint) {
        is RustTransportEndpoint.BleGatt ->
            TransportEndpoint.BleGatt(selected.serviceUuid, selected.writeUuid, selected.notifyUuid)

        is RustTransportEndpoint.SppRfcomm -> TransportEndpoint.SppRfcomm(selected.address, selected.serviceUuid)
    },
    selectionReasons.of(reason),
)

private fun RustSettingOperation.toKotlin(): SettingOperation = SettingOperation(
    id = id.toLong(),
    sessionId = sessionId.toLong(),
    setting = setting.toKotlin(),
    requestedValue = requestedValue.toKotlin(),
    stage = settingStages.of(stage),
    error = error?.toKotlin(),
    writtenFrames = writtenFrames.toInt(),
    totalFrames = totalFrames.toInt(),
    confirmedFields = confirmedFields.map { it.toKotlin() }.toPersistentSet(),
)
