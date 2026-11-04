package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionRecovery
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/** Facts about the host's own Bluetooth platform that the application mirrors, never re-derives. */
data class HostPlatformFacts(val permission: PermissionState, val transport: TransportAvailability)

/** The part of a [ControlState] only the session owner can know. Settings, presentation and owner are composed locally. */
data class OwnedControlState(val platform: HostPlatformFacts, val state: ControlState)

/** Wire form of the owner's control state, session references and operations. */
object ControlWire {
    fun encodeOwned(platform: HostPlatformFacts, state: ControlState): Wire = obj(
        "permission" to Wire.Str(platform.permission.name),
        "transport" to encode(platform.transport),
        "connection" to encode(state.connection),
        "scan" to encode(state.scan),
        "candidates" to arr(state.candidates.map(::encode)),
        "bonded" to arr(state.bondedDevices.map(::encode)),
        "device" to state.device?.let(::encode),
        "error" to state.lastError?.let(::encode),
        "operations" to arr(state.settingOperations.map(::encode)),
        "selection" to state.transportSelection?.let(::encode),
        "recovery" to state.connectionRecovery?.let(::encode),
        "target" to state.connectionTarget?.let(::encode),
    )

    fun decodeOwned(wire: Wire): OwnedControlState {
        val node = wire.asObj()
        return OwnedControlState(
            platform = HostPlatformFacts(node.field("permission").asEnum(), decodeTransport(node.field("transport"))),
            state = ControlState(
                platform = io.github.dohex.openrose.core.PlatformState(PermissionState.NOT_REQUIRED),
                connection = decodeConnection(node.field("connection")),
                scan = decodeScan(node.field("scan")),
                candidates = node.arr("candidates").map(::decodeCandidate).toPersistentList(),
                bondedDevices = node.arr("bonded").map(::decodeCandidate).toPersistentList(),
                device = node.optional("device")?.let(::decodeDevice),
                lastError = node.optional("error")?.let(::decodeError),
                settingOperations = node.arr("operations").map(::decodeOperation).toPersistentList(),
                transportSelection = node.optional("selection")?.let(::decodeSelection),
                connectionRecovery = node.optional("recovery")?.let(::decodeRecovery),
                connectionTarget = node.optional("target")?.let(::decodeCandidate),
            ),
        )
    }

    fun encode(reference: SessionReference): Wire = obj("device" to str(reference.deviceId.value), "session" to num(reference.sessionId))
    fun decodeReference(wire: Wire): SessionReference =
        wire.asObj().let { SessionReference(DeviceId(it.str("device")), it.long("session")) }

    fun encode(error: ControlError): Wire = obj("code" to Wire.Str(error.code.name), "message" to str(error.message))
    fun decodeError(wire: Wire): ControlError =
        wire.asObj().let { ControlError(it.field("code").asEnum<ControlErrorCode>(), it.str("message")) }

    fun encode(result: OperationResult): Wire = when (result) {
        OperationResult.Success -> obj("ok" to bool(true))
        is OperationResult.Failure -> obj("ok" to bool(false), "error" to encode(result.error))
    }

    fun decodeResult(wire: Wire): OperationResult = wire.asObj().let {
        if (it.bool("ok")) OperationResult.Success else OperationResult.Failure(decodeError(it.field("error")))
    }

    fun encode(value: FieldValue): Wire = when (value) {
        is FieldValue.Number -> obj("t" to str("number"), "v" to num(value.value))
        is FieldValue.Toggle -> obj("t" to str("toggle"), "v" to bool(value.value))
        is FieldValue.Text -> obj("t" to str("text"), "v" to str(value.value))
        is FieldValue.IntegerList -> obj("t" to str("ints"), "v" to arr(value.values.map(::num)))
        is FieldValue.Firmware -> obj("t" to str("firmware"), "parts" to arr(value.parts.map(::num)), "text" to str(value.displayText))
        is FieldValue.TouchKeys -> obj("t" to str("keys"), "v" to arr(value.bindings.map {
            obj("side" to str(it.side), "gesture" to str(it.gesture), "action" to it.action?.let(::str), "raw" to num(it.rawAction))
        }))
        is FieldValue.TouchAssignment -> obj("t" to str("assign"), "side" to str(value.side), "gesture" to str(value.gesture), "action" to str(value.action))
        is FieldValue.NoiseCycle -> obj("t" to str("cycle"), "v" to Wire.Obj(value.flags.mapValues { (_, flag) -> flag?.let(::bool) ?: Wire.Null }))
    }

    fun decodeValue(wire: Wire): FieldValue {
        val node = wire.asObj()
        return when (val type = node.str("t")) {
            "number" -> FieldValue.Number(node.int("v"))
            "toggle" -> FieldValue.Toggle(node.bool("v"))
            "text" -> FieldValue.Text(node.str("v"))
            "ints" -> FieldValue.IntegerList(node.arr("v").map { it.asInt() })
            "firmware" -> FieldValue.Firmware(node.arr("parts").map { it.asInt() }, node.str("text"))
            "keys" -> FieldValue.TouchKeys(node.arr("v").map {
                val binding = it.asObj()
                TouchBinding(binding.str("side"), binding.str("gesture"), binding.optional("action")?.asStr(), binding.int("raw"))
            })
            "assign" -> FieldValue.TouchAssignment(node.str("side"), node.str("gesture"), node.str("action"))
            "cycle" -> FieldValue.NoiseCycle(node.field("v").asObj().fields.mapValues { (_, flag) ->
                if (flag == Wire.Null) null else flag.asBool()
            })
            else -> throw WireFormatException("未知字段值类型 $type")
        }
    }

    private fun encode(reading: FieldReading): Wire = when (reading) {
        FieldReading.Loading -> obj("r" to str("loading"))
        is FieldReading.NotProvided -> obj("r" to str("none"), "raw" to str(reading.rawHex))
        is FieldReading.Unknown -> obj("r" to str("unknown"), "raw" to str(reading.rawHex))
        is FieldReading.Known -> obj("r" to str("known"), "raw" to str(reading.rawHex), "value" to encode(reading.value))
    }

    private fun decodeReading(wire: Wire): FieldReading {
        val node = wire.asObj()
        return when (val kind = node.str("r")) {
            "loading" -> FieldReading.Loading
            "none" -> FieldReading.NotProvided(node.str("raw"))
            "unknown" -> FieldReading.Unknown(node.str("raw"))
            "known" -> FieldReading.Known(decodeValue(node.field("value")), node.str("raw"))
            else -> throw WireFormatException("未知读数类型 $kind")
        }
    }

    private fun encode(availability: TransportAvailability): Wire = when (availability) {
        TransportAvailability.Available -> obj("t" to str("available"))
        TransportAvailability.Disabled -> obj("t" to str("disabled"))
        is TransportAvailability.Unavailable -> obj("t" to str("unavailable"), "reason" to str(availability.reason))
    }

    private fun decodeTransport(wire: Wire): TransportAvailability {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "available" -> TransportAvailability.Available
            "disabled" -> TransportAvailability.Disabled
            "unavailable" -> TransportAvailability.Unavailable(node.str("reason"))
            else -> throw WireFormatException("未知传输状态 $kind")
        }
    }

    private fun encode(connection: ConnectionState): Wire = when (connection) {
        ConnectionState.Disconnected -> obj("t" to str("disconnected"))
        is ConnectionState.Connecting -> obj(
            "t" to str("connecting"), "device" to str(connection.deviceId.value),
            "session" to num(connection.sessionId), "phase" to Wire.Str(connection.phase.name),
        )
        is ConnectionState.Connected -> obj(
            "t" to str("connected"), "device" to str(connection.deviceId.value), "session" to num(connection.sessionId),
            "profile" to Wire.Str(connection.profile.name), "phase" to Wire.Str(connection.phase.name),
        )
    }

    private fun decodeConnection(wire: Wire): ConnectionState {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "disconnected" -> ConnectionState.Disconnected
            "connecting" -> ConnectionState.Connecting(
                DeviceId(node.str("device")), node.long("session"), node.field("phase").asEnum<ConnectionPhase>(),
            )
            "connected" -> ConnectionState.Connected(
                DeviceId(node.str("device")), node.long("session"), node.field("profile").asEnum<ModelProfile>(),
                node.field("phase").asEnum<ConnectionPhase>(),
            )
            else -> throw WireFormatException("未知连接状态 $kind")
        }
    }

    private fun encode(scan: ScanState): Wire = when (scan) {
        ScanState.Idle -> obj("t" to str("idle"))
        ScanState.Scanning -> obj("t" to str("scanning"))
        is ScanState.Failed -> obj("t" to str("failed"), "error" to encode(scan.error))
    }

    private fun decodeScan(wire: Wire): ScanState {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "idle" -> ScanState.Idle
            "scanning" -> ScanState.Scanning
            "failed" -> ScanState.Failed(decodeError(node.field("error")))
            else -> throw WireFormatException("未知扫描状态 $kind")
        }
    }

    private fun encode(candidate: DeviceCandidate): Wire = obj(
        "id" to str(candidate.deviceId.value), "name" to candidate.name?.let(::str), "rssi" to candidate.rssi?.let(::num),
        "profile" to candidate.profile?.let { Wire.Str(it.name) }, "manufacturer" to str(candidate.manufacturerDataHex),
        "classic" to candidate.classicAddress?.let(::str),
    )

    private fun decodeCandidate(wire: Wire): DeviceCandidate = wire.asObj().let {
        DeviceCandidate(
            DeviceId(it.str("id")), it.optional("name")?.asStr(), it.optional("rssi")?.asInt(),
            it.optional("profile")?.asEnum<ModelProfile>(), it.str("manufacturer"), it.optional("classic")?.asStr(),
        )
    }

    private fun encode(device: DeviceState): Wire = obj(
        "id" to str(device.deviceId.value), "session" to num(device.sessionId), "profile" to Wire.Str(device.profile.name),
        "capabilities" to encode(device.capabilities),
        "fields" to arr(device.fields.map { (field, reading) -> obj("f" to Wire.Str(field.name), "reading" to encode(reading)) }),
        "fieldErrors" to arr(device.fieldErrors.map { (field, error) -> obj("f" to Wire.Str(field.name), "error" to encode(error)) }),
        "raw" to arr(device.rawNotifications.map(::str)), "unknown" to arr(device.unknownNotifications.map(::str)),
        "warnings" to arr(device.warnings.map(::str)), "classic" to device.classicAddress?.let(::str),
    )

    private fun decodeDevice(wire: Wire): DeviceState = wire.asObj().let { node ->
        DeviceState(
            deviceId = DeviceId(node.str("id")), sessionId = node.long("session"),
            profile = node.field("profile").asEnum(), capabilities = decodeCapabilities(node.field("capabilities")),
            fields = node.arr("fields").associate {
                val entry = it.asObj(); entry.field("f").asEnum<DeviceField>() to decodeReading(entry.field("reading"))
            }.toPersistentMap(),
            fieldErrors = node.arr("fieldErrors").associate {
                val entry = it.asObj(); entry.field("f").asEnum<DeviceField>() to decodeError(entry.field("error"))
            }.toPersistentMap(),
            rawNotifications = node.arr("raw").map { it.asStr() }.toPersistentList(), unknownNotifications = node.arr("unknown").map { it.asStr() }.toPersistentList(),
            warnings = node.arr("warnings").map { it.asStr() }.toPersistentList(), classicAddress = node.optional("classic")?.asStr(),
        )
    }

    private fun encode(capabilities: DeviceCapabilities): Wire = obj(
        "readable" to arr(capabilities.readableFields.map { Wire.Str(it.name) }),
        "writable" to arr(capabilities.writableSettings.map { Wire.Str(it.name) }),
        "noise" to capabilities.noiseControl?.let(::encode),
        "equalizer" to capabilities.equalizer?.let(::encode),
        "touch" to capabilities.touchControl?.let { touch ->
            obj("slots" to arr(touch.slots.map { obj("side" to str(it.side), "gesture" to str(it.gesture)) }),
                "actions" to arr(touch.actions.map(::str)))
        },
        "audio" to capabilities.audioControl?.let { audio ->
            obj("modes" to arr(audio.modes.map(::str)), "coupled" to bool(audio.multipointCoupled))
        },
        "promptVolume" to arr(capabilities.promptVolumeLevels.map(::num)),
        "gain" to arr(capabilities.gainLevels.map(::num)),
        "hearingProtection" to arr(capabilities.hearingProtectionLevels.map(::num)),
    )

    private fun decodeCapabilities(wire: Wire): DeviceCapabilities = wire.asObj().let { node ->
        DeviceCapabilities(
            readableFields = node.arr("readable").map { it.asEnum<DeviceField>() }.toPersistentSet(),
            writableSettings = node.arr("writable").map { it.asEnum<DeviceSetting>() }.toPersistentSet(),
            noiseControl = node.optional("noise")?.let(::decodeNoise),
            equalizer = node.optional("equalizer")?.let(::decodeEqualizer),
            touchControl = node.optional("touch")?.asObj()?.let { touch ->
                TouchControlCapabilities(
                    touch.arr("slots").map { it.asObj().let { slot -> TouchSlot(slot.str("side"), slot.str("gesture")) } }.toPersistentSet(),
                    touch.arr("actions").map { it.asStr() }.toPersistentSet(),
                )
            },
            audioControl = node.optional("audio")?.asObj()?.let { audio ->
                AudioControlCapabilities(audio.arr("modes").map { it.asStr() }.toPersistentSet(), audio.bool("coupled"))
            },
            promptVolumeLevels = node.arr("promptVolume").map { it.asInt() }.toPersistentSet(),
            gainLevels = node.arr("gain").map { it.asInt() }.toPersistentSet(),
            hearingProtectionLevels = node.arr("hearingProtection").map { it.asInt() }.toPersistentSet(),
        )
    }

    private fun encode(noise: NoiseControlCapabilities): Wire = obj(
        "modes" to arr(noise.modes.map(::str)), "cycle" to arr(noise.cycleModes.map(::str)),
        "anc" to noise.ancLevel?.let(::encode), "transparency" to noise.transparencyLevel?.let(::encode),
        "minimumCycle" to num(noise.minimumCycleSelections),
    )

    private fun encode(levels: NoiseLevelCapabilities): Wire =
        obj("levels" to arr(levels.levels.map(::num)), "modes" to arr(levels.visibleModes.map(::str)))

    private fun decodeLevels(wire: Wire): NoiseLevelCapabilities = wire.asObj().let {
        NoiseLevelCapabilities(it.arr("levels").map { level -> level.asInt() }.toPersistentSet(), it.arr("modes").map { mode -> mode.asStr() }.toPersistentSet())
    }

    private fun decodeNoise(wire: Wire): NoiseControlCapabilities = wire.asObj().let {
        NoiseControlCapabilities(
            modes = it.arr("modes").map { mode -> mode.asStr() }.toPersistentSet(), cycleModes = it.arr("cycle").map { mode -> mode.asStr() }.toPersistentSet(),
            ancLevel = it.optional("anc")?.let(::decodeLevels), transparencyLevel = it.optional("transparency")?.let(::decodeLevels),
            minimumCycleSelections = it.int("minimumCycle"),
        )
    }

    private fun encode(equalizer: EqualizerCapabilities): Wire = obj(
        "presets" to arr(equalizer.presets.map(::str)),
        "custom" to equalizer.custom?.let {
            obj("bands" to arr(it.bandsHz.map(::num)), "min" to num(it.minGain), "max" to num(it.maxGain))
        },
    )

    private fun decodeEqualizer(wire: Wire): EqualizerCapabilities = wire.asObj().let { node ->
        EqualizerCapabilities(
            node.arr("presets").map { it.asStr() }.toPersistentSet(),
            node.optional("custom")?.asObj()?.let { custom ->
                CustomEqualizerCapabilities(custom.arr("bands").map { it.asInt() }.toPersistentList(), custom.int("min"), custom.int("max"))
            },
        )
    }

    private fun encode(operation: SettingOperation): Wire = obj(
        "id" to num(operation.id), "session" to num(operation.sessionId), "setting" to Wire.Str(operation.setting.name),
        "value" to encode(operation.requestedValue), "stage" to Wire.Str(operation.stage.name),
        "error" to operation.error?.let(::encode), "written" to num(operation.writtenFrames), "total" to num(operation.totalFrames),
        "confirmed" to arr(operation.confirmedFields.map { Wire.Str(it.name) }),
    )

    private fun decodeOperation(wire: Wire): SettingOperation = wire.asObj().let { node ->
        SettingOperation(
            id = node.long("id"), sessionId = node.long("session"), setting = node.field("setting").asEnum(),
            requestedValue = decodeValue(node.field("value")), stage = node.field("stage").asEnum<SettingStage>(),
            error = node.optional("error")?.let(::decodeError), writtenFrames = node.int("written"), totalFrames = node.int("total"),
            confirmedFields = node.arr("confirmed").map { it.asEnum<DeviceField>() }.toPersistentSet(),
        )
    }

    private fun encode(selection: TransportSelection): Wire = obj(
        "reason" to Wire.Str(selection.reason.name),
        "endpoint" to when (val endpoint = selection.endpoint) {
            is TransportEndpoint.BleGatt -> obj(
                "t" to str("ble"), "service" to str(endpoint.serviceUuid), "write" to str(endpoint.writeUuid), "notify" to str(endpoint.notifyUuid),
            )
            is TransportEndpoint.SppRfcomm -> obj("t" to str("spp"), "address" to str(endpoint.address), "service" to str(endpoint.serviceUuid))
        },
    )

    private fun decodeSelection(wire: Wire): TransportSelection = wire.asObj().let { node ->
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

    private fun encode(recovery: ConnectionRecovery): Wire =
        obj("session" to num(recovery.sessionId), "reason" to Wire.Str(recovery.reason.name), "attempt" to num(recovery.attempt))

    private fun decodeRecovery(wire: Wire): ConnectionRecovery = wire.asObj().let {
        ConnectionRecovery(it.long("session"), it.field("reason").asEnum<TransportRecoveryReason>(), it.int("attempt"))
    }
}
