package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.BridgeIpcState
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostFrame
import io.github.dohex.openrose.androidintegration.HostPlatformFacts
import io.github.dohex.openrose.androidintegration.IslandPrompt
import io.github.dohex.openrose.androidintegration.PresentationReading
import io.github.dohex.openrose.androidintegration.SystemBatteryState
import io.github.dohex.openrose.androidintegration.SystemEndpointDiagnostics
import io.github.dohex.openrose.androidintegration.SystemNoiseState
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.decodeControlSnapshot
import io.github.dohex.openrose.ffi.encodeToBytes
import uniffi.openrose_control.ControlSnapshot
import kotlin.io.encoding.Base64

class HostSnapshotFormatException(val error: CoreDecodeResult.Error) : WireFormatException(
    when (error) {
        is CoreDecodeResult.Error.Malformed -> "宿主状态快照损坏：${error.detail}"
        is CoreDecodeResult.Error.UnsupportedVersion -> "宿主状态快照版本不兼容：期望 ${error.expected}，实际 ${error.actual}"
    },
)

object HostFrameWire {
    fun encode(frame: HostFrame): Wire = obj(
        "generation" to HostIdentityWire.encode(frame.generation),
        "holder" to HostIdentityWire.encode(frame.holder),
        "platform" to encodePlatform(frame.platform),
        "snapshot" to str(Base64.encode(frame.snapshot.encodeToBytes())),
        "presentation" to encodePresentation(frame.presentation),
        "facts" to encode(frame.facts),
    )

    fun decodeFrame(wire: Wire): HostFrame {
        val node = wire.asObj()
        return HostFrame(
            HostIdentityWire.decodeGeneration(node.field("generation")),
            HostIdentityWire.decodeHolder(node.field("holder")),
            decodePlatform(node.field("platform")),
            decodeSnapshot(node.str("snapshot")),
            decodePresentation(node.field("presentation")),
            decodeFacts(node.field("facts")),
        )
    }

    private fun encodePlatform(platform: HostPlatformFacts): Wire = obj(
        "permission" to Wire.Str(platform.permission.name),
        "scanPermission" to Wire.Str(platform.scanPermission.name),
        "transport" to encode(platform.transport),
    )

    private fun decodePlatform(wire: Wire): HostPlatformFacts = wire.asObj().let { node ->
        HostPlatformFacts(
            node.field("permission").asEnum(),
            decodeTransport(node.field("transport")),
            node.field("scanPermission").asEnum(),
        )
    }

    private fun decodeSnapshot(encoded: String): ControlSnapshot {
        val bytes = try {
            Base64.decode(encoded)
        } catch (failure: IllegalArgumentException) {
            throw WireFormatException("宿主状态快照编码无效", failure)
        }
        return when (val decoded = decodeControlSnapshot(bytes)) {
            is CoreDecodeResult.Ok -> decoded.value
            is CoreDecodeResult.Error -> throw HostSnapshotFormatException(decoded)
        }
    }

    private fun encodePresentation(state: SystemPresentationState): Wire = obj(
        "reference" to state.reference?.let(ControlWire::encode), "tag" to state.notificationTag?.let(::str),
        "mode" to Wire.Str(state.islandMode.name), "statusNotification" to bool(state.statusNotificationEnabled),
        "summary" to bool(state.islandSummaryEnabled),
        "islandFields" to bool(state.allowIslandFields), "target" to Wire.Str(state.statusNotificationTarget.name),
        "dynamic" to bool(state.dynamicColor),
        "battery" to state.battery?.let { battery ->
            obj(
                "left" to encodeReading(battery.left, ::num),
                "right" to encodeReading(battery.right, ::num),
                "case" to encodeReading(battery.case, ::num),
            )
        },
        "noise" to state.noise?.let { noise ->
            obj(
                "mode" to encodeReading(noise.mode, ::str),
                "anc" to encodeReading(noise.ancLevel, ::num),
                "transparency" to encodeReading(noise.transparencyLevel, ::num),
            )
        },
        "actions" to arr(state.actions.map { Wire.Str(it.name) }),
        "prompt" to state.prompt?.let { prompt ->
            obj(
                "id" to str(prompt.id),
                "timing" to Wire.Str(prompt.timing.name),
                "mode" to Wire.Str(prompt.mode.name),
                "reference" to ControlWire.encode(prompt.reference),
                "claimed" to bool(prompt.claimed),
                "duration" to num(prompt.durationMillis),
            )
        },
    )

    private fun decodePresentation(wire: Wire): SystemPresentationState = wire.asObj().let { node ->
        SystemPresentationState(
            reference = node.optional("reference")?.let(ControlWire::decodeReference),
            notificationTag = node.optional("tag")?.asStr(), islandMode = node.field("mode").asEnum(),
            statusNotificationEnabled = node.bool("statusNotification"), islandSummaryEnabled = node.bool("summary"),
            allowIslandFields = node.bool("islandFields"),
            statusNotificationTarget = node.field("target").asEnum(), dynamicColor = node.bool("dynamic"),
            battery = node.optional("battery")?.asObj()?.let {
                SystemBatteryState(
                    decodeReading(it.field("left"), Wire::asInt),
                    decodeReading(it.field("right"), Wire::asInt),
                    decodeReading(it.field("case"), Wire::asInt),
                )
            },
            noise = node.optional("noise")?.asObj()?.let {
                SystemNoiseState(
                    decodeReading(it.field("mode"), Wire::asStr),
                    decodeReading(it.field("anc"), Wire::asInt),
                    decodeReading(it.field("transparency"), Wire::asInt),
                )
            },
            actions = node.arr("actions").map { it.asEnum<DeviceSetting>() }.toSet(),
            prompt = node.optional("prompt")?.asObj()?.let {
                IslandPrompt(
                    it.str("id"),
                    it.field("timing").asEnum(),
                    it.field("mode").asEnum(),
                    ControlWire.decodeReference(it.field("reference")),
                    it.bool("claimed"),
                    it.long("duration"),
                )
            },
        )
    }

    private fun <T> encodeReading(reading: PresentationReading<T>, encode: (T) -> Wire): Wire = when (reading) {
        PresentationReading.Loading -> obj("t" to str("loading"))
        PresentationReading.Unknown -> obj("t" to str("unknown"))
        PresentationReading.NotProvided -> obj("t" to str("none"))
        is PresentationReading.Known -> obj("t" to str("known"), "v" to encode(reading.value))
    }

    private fun <T> decodeReading(wire: Wire, decode: (Wire) -> T): PresentationReading<T> = wire.asObj().let {
        when (val kind = it.str("t")) {
            "loading" -> PresentationReading.Loading
            "unknown" -> PresentationReading.Unknown
            "none" -> PresentationReading.NotProvided
            "known" -> PresentationReading.Known(decode(it.field("v")))
            else -> throw WireFormatException("未知呈现读数 $kind")
        }
    }

    private fun encode(facts: HostFacts): Wire = obj(
        "presentation" to Wire.Obj(facts.presentation.mapValues { (_, value) -> Wire.Str(value) }),
        "endpoints" to Wire.Obj(
            facts.endpoints.mapValues { (_, endpoint) ->
                obj(
                    "package" to str(endpoint.targetPackage),
                    "process" to str(endpoint.targetProcess),
                    "version" to str(endpoint.componentVersion),
                    "entry" to str(endpoint.entryMatch),
                    "ipc" to Wire.Str(endpoint.ipc.name),
                    "features" to Wire.Obj(endpoint.features.mapValues { (_, value) -> Wire.Str(value) }),
                )
            },
        ),
    )

    private fun decodeFacts(wire: Wire): HostFacts = wire.asObj().let { node ->
        HostFacts(
            endpoints = node.field("endpoints").asObj().fields.mapValues { (_, value) ->
                val endpoint = value.asObj()
                SystemEndpointDiagnostics(
                    endpoint.str("package"),
                    endpoint.str("process"),
                    endpoint.str("version"),
                    endpoint.str("entry"),
                    endpoint.field("ipc").asEnum<BridgeIpcState>(),
                    endpoint.field("features").asObj().fields.mapValues { (_, text) -> text.asStr() },
                )
            },
            presentation = node.field("presentation").asObj().fields.mapValues { (_, text) -> text.asStr() },
        )
    }
}
