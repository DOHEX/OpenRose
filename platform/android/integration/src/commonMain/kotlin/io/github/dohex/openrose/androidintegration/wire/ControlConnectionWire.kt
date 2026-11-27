package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionRecovery
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason

internal fun encode(availability: TransportAvailability): Wire = when (availability) {
    TransportAvailability.Available -> obj("t" to str("available"))
    TransportAvailability.Disabled -> obj("t" to str("disabled"))
    is TransportAvailability.Unavailable -> UnavailableReasonWire.encode(availability.reason)
}

private object UnavailableReasonWire {
    private val fixedReasons = mapOf(
        "BLE_UNSUPPORTED" to TransportUnavailableReason.BleUnsupported,
        "PERMISSION_REQUIRED" to TransportUnavailableReason.PermissionRequired,
        "NO_ADAPTER" to TransportUnavailableReason.NoAdapter,
        "ADAPTER_POWERED_OFF" to TransportUnavailableReason.AdapterPoweredOff,
        "ADAPTER_STATE_UNKNOWN" to TransportUnavailableReason.AdapterStateUnknown,
        "ACCESS_DENIED" to TransportUnavailableReason.AccessDenied,
        "SERVICE_UNAVAILABLE" to TransportUnavailableReason.ServiceUnavailable,
        "INITIALIZING" to TransportUnavailableReason.Initializing,
        "RECOVERING" to TransportUnavailableReason.Recovering,
        "SHUTTING_DOWN" to TransportUnavailableReason.ShuttingDown,
        "CLOSED" to TransportUnavailableReason.Closed,
    )

    private val fixedCodes = fixedReasons.entries.associate { (code, reason) -> reason to code }

    fun encode(reason: TransportUnavailableReason): Wire = when (reason) {
        is TransportUnavailableReason.UnsupportedPlatform -> obj(
            "t" to str("unavailable"),
            "code" to str("UNSUPPORTED_PLATFORM"),
            "platform" to reason.platform?.let(::str),
        )

        is TransportUnavailableReason.MissingDependency -> obj(
            "t" to str("unavailable"),
            "code" to str("MISSING_DEPENDENCY"),
            "detail" to reason.detail?.let(::str),
        )

        is TransportUnavailableReason.BackendUnavailable -> obj(
            "t" to str("unavailable"),
            "code" to str("BACKEND_UNAVAILABLE"),
            "detail" to reason.detail?.let(::str),
        )

        else -> obj(
            "t" to str("unavailable"),
            "code" to str(fixedCodes.getValue(reason)),
        )
    }

    fun decode(node: Wire.Obj): TransportUnavailableReason = when (val code = node.str("code")) {
        "UNSUPPORTED_PLATFORM" -> TransportUnavailableReason.UnsupportedPlatform(node.optional("platform")?.asStr())
        "MISSING_DEPENDENCY" -> TransportUnavailableReason.MissingDependency(node.optional("detail")?.asStr())
        "BACKEND_UNAVAILABLE" -> TransportUnavailableReason.BackendUnavailable(node.optional("detail")?.asStr())
        else -> fixedReasons[code] ?: throw WireFormatException("未知传输不可用原因 $code")
    }
}

internal fun decodeTransport(wire: Wire): TransportAvailability {
    val node = wire.asObj()
    return when (val kind = node.str("t")) {
        "available" -> TransportAvailability.Available
        "disabled" -> TransportAvailability.Disabled
        "unavailable" -> TransportAvailability.Unavailable(UnavailableReasonWire.decode(node))
        else -> throw WireFormatException("未知传输状态 $kind")
    }
}

internal fun encode(connection: ConnectionState): Wire = when (connection) {
    ConnectionState.Disconnected -> obj("t" to str("disconnected"))

    is ConnectionState.Connecting -> obj(
        "t" to str("connecting"),
        "device" to str(connection.deviceId.value),
        "session" to num(connection.sessionId),
        "phase" to Wire.Str(connection.phase.name),
    )

    is ConnectionState.Connected -> obj(
        "t" to str("connected"),
        "device" to str(connection.deviceId.value),
        "session" to num(connection.sessionId),
        "profile" to Wire.Str(connection.profile.name),
        "phase" to Wire.Str(connection.phase.name),
    )
}

internal fun decodeConnection(wire: Wire): ConnectionState {
    val node = wire.asObj()
    return when (val kind = node.str("t")) {
        "disconnected" -> ConnectionState.Disconnected

        "connecting" -> ConnectionState.Connecting(
            DeviceId(node.str("device")),
            node.long("session"),
            node.field("phase").asEnum<ConnectionPhase>(),
        )

        "connected" -> ConnectionState.Connected(
            DeviceId(node.str("device")),
            node.long("session"),
            node.field("profile").asEnum<ModelProfile>(),
            node.field("phase").asEnum<ConnectionPhase>(),
        )

        else -> throw WireFormatException("未知连接状态 $kind")
    }
}

internal fun encode(scan: ScanState): Wire = when (scan) {
    ScanState.Idle -> obj("t" to str("idle"))
    ScanState.Scanning -> obj("t" to str("scanning"))
    is ScanState.Failed -> obj("t" to str("failed"), "error" to ControlWire.encode(scan.error))
}

internal fun decodeScan(wire: Wire): ScanState {
    val node = wire.asObj()
    return when (val kind = node.str("t")) {
        "idle" -> ScanState.Idle
        "scanning" -> ScanState.Scanning
        "failed" -> ScanState.Failed(ControlWire.decodeError(node.field("error")))
        else -> throw WireFormatException("未知扫描状态 $kind")
    }
}

internal fun encode(selection: TransportSelection): Wire = obj(
    "reason" to Wire.Str(selection.reason.name),
    "endpoint" to when (val endpoint = selection.endpoint) {
        is TransportEndpoint.BleGatt -> obj(
            "t" to str("ble"),
            "service" to str(endpoint.serviceUuid),
            "write" to str(endpoint.writeUuid),
            "notify" to str(endpoint.notifyUuid),
        )

        is TransportEndpoint.SppRfcomm -> obj(
            "t" to str("spp"),
            "address" to str(endpoint.address),
            "service" to str(endpoint.serviceUuid),
        )
    },
)

internal fun decodeSelection(wire: Wire): TransportSelection = wire.asObj().let { node ->
    val endpoint = node.field("endpoint").asObj()
    TransportSelection(
        when (val kind = endpoint.str("t")) {
            "ble" -> TransportEndpoint.BleGatt(endpoint.str("service"), endpoint.str("write"), endpoint.str("notify"))
            "spp" -> TransportEndpoint.SppRfcomm(endpoint.str("address"), endpoint.str("service"))
            else -> throw WireFormatException("未知传输端点 $kind")
        },
        node.field("reason").asEnum<TransportSelectionReason>(),
    )
}

internal fun encode(recovery: ConnectionRecovery): Wire = obj(
    "session" to num(recovery.sessionId),
    "reason" to Wire.Str(recovery.reason.name),
    "attempt" to num(recovery.attempt),
)

internal fun decodeRecovery(wire: Wire): ConnectionRecovery = wire.asObj().let {
    ConnectionRecovery(it.long("session"), it.field("reason").asEnum<TransportRecoveryReason>(), it.int("attempt"))
}
