package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.decodeControlCommand
import io.github.dohex.openrose.ffi.encodeToBytes
import uniffi.openrose_control.ControlCommand
import uniffi.openrose_control.SessionReference as RustSessionReference
import uniffi.openrose_headset.HeadsetCommand as RustHeadsetCommand
import uniffi.openrose_headset.NoiseMode as RustNoiseMode

object HostWire {
    fun encode(command: HostCommand): ByteArray = command.toRust().encodeToBytes()

    fun decodeCommand(bytes: ByteArray): CoreDecodeResult<HostCommand> = when (val result = decodeControlCommand(bytes)) {
        is CoreDecodeResult.Ok -> CoreDecodeResult.Ok(result.value.toKotlin())
        is CoreDecodeResult.Error -> result
    }

    fun encode(result: HostCallResult): Wire = when (result) {
        is HostCallResult.Operation -> obj("t" to str("operation"), "result" to ControlWire.encode(result.result))

        is HostCallResult.Flag -> obj("t" to str("flag"), "value" to bool(result.value))

        is HostCallResult.Rejected -> obj(
            "t" to str("rejected"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
        )
    }

    fun decodeCallResult(wire: Wire): HostCallResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "operation" -> HostCallResult.Operation(ControlWire.decodeResult(node.field("result")))
            "flag" -> HostCallResult.Flag(node.bool("value"))
            "rejected" -> HostCallResult.Rejected(node.field("rejection").asEnum<HostRejection>(), node.str("message"))
            else -> throw WireFormatException("未知调用结果 $kind")
        }
    }
}

private fun HostCommand.toRust(): ControlCommand = when (this) {
    HostCommand.RefreshBondedDevices -> ControlCommand.RefreshBondedHeadsets
    HostCommand.StartScan -> ControlCommand.StartScan
    HostCommand.StopScan -> ControlCommand.StopScan
    is HostCommand.SelectCurrentHeadset -> ControlCommand.SelectCurrentHeadset(deviceId.value)
    is HostCommand.Connect -> ControlCommand.Connect(deviceId.value)
    is HostCommand.ConnectFromSystem -> ControlCommand.ConnectPinned(deviceId.value)
    is HostCommand.ConnectForAddDevice -> ControlCommand.ConnectForAddHeadset(deviceId.value)
    HostCommand.Disconnect -> ControlCommand.Disconnect
    is HostCommand.Set -> ControlCommand.Set(reference?.toRust(), command.toRust())
    is HostCommand.ClaimPrompt -> ControlCommand.ClaimPrompt(reference.toRust(), promptId)
}

private fun ControlCommand.toKotlin(): HostCommand = when (this) {
    ControlCommand.RefreshBondedHeadsets -> HostCommand.RefreshBondedDevices
    ControlCommand.StartScan -> HostCommand.StartScan
    ControlCommand.StopScan -> HostCommand.StopScan
    is ControlCommand.SelectCurrentHeadset -> HostCommand.SelectCurrentHeadset(DeviceId(headsetId))
    is ControlCommand.Connect -> HostCommand.Connect(DeviceId(headsetId))
    is ControlCommand.ConnectPinned -> HostCommand.ConnectFromSystem(DeviceId(headsetId))
    is ControlCommand.ConnectForAddHeadset -> HostCommand.ConnectForAddDevice(DeviceId(headsetId))
    ControlCommand.Disconnect -> HostCommand.Disconnect
    is ControlCommand.Set -> HostCommand.Set(reference?.toKotlin(), command.toKotlin())
    is ControlCommand.ClaimPrompt -> HostCommand.ClaimPrompt(reference.toKotlin(), promptId)
}

private fun SessionReference.toRust(): RustSessionReference = RustSessionReference(deviceId.value, sessionId.toULong())

private fun RustSessionReference.toKotlin(): SessionReference = SessionReference(DeviceId(headsetId), sessionId.toLong())

@Suppress("CyclomaticComplexMethod")
private fun HeadsetCommand.toRust(): RustHeadsetCommand = when (this) {
    is HeadsetCommand.NoiseMode -> RustHeadsetCommand.NoiseMode(mode.toRust())
    is HeadsetCommand.AncLevel -> RustHeadsetCommand.AncLevel(level)
    is HeadsetCommand.TransparencyLevel -> RustHeadsetCommand.TransparencyLevel(level)
    is HeadsetCommand.NoiseCycle -> RustHeadsetCommand.NoiseCycle(flags)
    is HeadsetCommand.EqualizerPreset -> RustHeadsetCommand.EqualizerPreset(preset)
    is HeadsetCommand.CustomEqualizer -> RustHeadsetCommand.CustomEqualizer(gains)
    is HeadsetCommand.TouchBinding -> RustHeadsetCommand.TouchBinding(side, gesture, action)
    is HeadsetCommand.TapBinding -> RustHeadsetCommand.TapBinding(side, gesture, action)
    is HeadsetCommand.PromptVolume -> RustHeadsetCommand.PromptVolume(level)
    is HeadsetCommand.Gain -> RustHeadsetCommand.Gain(level)
    is HeadsetCommand.HearingProtection -> RustHeadsetCommand.HearingProtection(level)
    is HeadsetCommand.BassBoost -> RustHeadsetCommand.BassBoost(level)
    is HeadsetCommand.GameMode -> RustHeadsetCommand.GameMode(enabled)
    is HeadsetCommand.Multipoint -> RustHeadsetCommand.Multipoint(enabled)
    is HeadsetCommand.TouchEnabled -> RustHeadsetCommand.TouchEnabled(enabled)
    is HeadsetCommand.EarDetection -> RustHeadsetCommand.EarDetection(enabled)
    is HeadsetCommand.TapEnabled -> RustHeadsetCommand.TapEnabled(enabled)
    is HeadsetCommand.SpatialAudio -> RustHeadsetCommand.SpatialAudio(enabled)
    is HeadsetCommand.EarAdaptive -> RustHeadsetCommand.EarAdaptive(enabled)
    is HeadsetCommand.IndependentMicActivation -> RustHeadsetCommand.IndependentMicActivation(enabled)
    is HeadsetCommand.IndependentMicMode -> RustHeadsetCommand.IndependentMicMode(enabled)
    is HeadsetCommand.AudioMode -> RustHeadsetCommand.AudioMode(mode)
    is HeadsetCommand.PromptLanguage -> RustHeadsetCommand.PromptLanguage(language)
    is HeadsetCommand.SpatialMode -> RustHeadsetCommand.SpatialMode(mode)
    is HeadsetCommand.SpatialContent -> RustHeadsetCommand.SpatialContent(content)
}

@Suppress("CyclomaticComplexMethod")
private fun RustHeadsetCommand.toKotlin(): HeadsetCommand = when (this) {
    is RustHeadsetCommand.NoiseMode -> HeadsetCommand.NoiseMode(v1.toKotlin())
    is RustHeadsetCommand.AncLevel -> HeadsetCommand.AncLevel(v1)
    is RustHeadsetCommand.TransparencyLevel -> HeadsetCommand.TransparencyLevel(v1)
    is RustHeadsetCommand.NoiseCycle -> HeadsetCommand.NoiseCycle(v1)
    is RustHeadsetCommand.EqualizerPreset -> HeadsetCommand.EqualizerPreset(v1)
    is RustHeadsetCommand.CustomEqualizer -> HeadsetCommand.CustomEqualizer(v1)
    is RustHeadsetCommand.TouchBinding -> HeadsetCommand.TouchBinding(side, gesture, action)
    is RustHeadsetCommand.TapBinding -> HeadsetCommand.TapBinding(side, gesture, action)
    is RustHeadsetCommand.PromptVolume -> HeadsetCommand.PromptVolume(v1)
    is RustHeadsetCommand.Gain -> HeadsetCommand.Gain(v1)
    is RustHeadsetCommand.HearingProtection -> HeadsetCommand.HearingProtection(v1)
    is RustHeadsetCommand.BassBoost -> HeadsetCommand.BassBoost(v1)
    is RustHeadsetCommand.GameMode -> HeadsetCommand.GameMode(v1)
    is RustHeadsetCommand.Multipoint -> HeadsetCommand.Multipoint(v1)
    is RustHeadsetCommand.TouchEnabled -> HeadsetCommand.TouchEnabled(v1)
    is RustHeadsetCommand.EarDetection -> HeadsetCommand.EarDetection(v1)
    is RustHeadsetCommand.TapEnabled -> HeadsetCommand.TapEnabled(v1)
    is RustHeadsetCommand.SpatialAudio -> HeadsetCommand.SpatialAudio(v1)
    is RustHeadsetCommand.EarAdaptive -> HeadsetCommand.EarAdaptive(v1)
    is RustHeadsetCommand.IndependentMicActivation -> HeadsetCommand.IndependentMicActivation(v1)
    is RustHeadsetCommand.IndependentMicMode -> HeadsetCommand.IndependentMicMode(v1)
    is RustHeadsetCommand.AudioMode -> HeadsetCommand.AudioMode(v1)
    is RustHeadsetCommand.PromptLanguage -> HeadsetCommand.PromptLanguage(v1)
    is RustHeadsetCommand.SpatialMode -> HeadsetCommand.SpatialMode(v1)
    is RustHeadsetCommand.SpatialContent -> HeadsetCommand.SpatialContent(v1)
}

private fun NoiseMode.toRust(): RustNoiseMode = when (this) {
    NoiseMode.NORMAL -> RustNoiseMode.NORMAL
    NoiseMode.ANC -> RustNoiseMode.ANC
    NoiseMode.TRANSPARENCY -> RustNoiseMode.TRANSPARENCY
    NoiseMode.WIND -> RustNoiseMode.WIND
    NoiseMode.AUTO -> RustNoiseMode.AUTO
    NoiseMode.DYNAMIC -> RustNoiseMode.DYNAMIC
    NoiseMode.DEEP -> RustNoiseMode.DEEP
}

private fun RustNoiseMode.toKotlin(): NoiseMode = when (this) {
    RustNoiseMode.NORMAL -> NoiseMode.NORMAL
    RustNoiseMode.ANC -> NoiseMode.ANC
    RustNoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    RustNoiseMode.WIND -> NoiseMode.WIND
    RustNoiseMode.AUTO -> NoiseMode.AUTO
    RustNoiseMode.DYNAMIC -> NoiseMode.DYNAMIC
    RustNoiseMode.DEEP -> NoiseMode.DEEP
}
