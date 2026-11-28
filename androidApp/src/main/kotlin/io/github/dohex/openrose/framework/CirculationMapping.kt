package io.github.dohex.openrose.framework

import io.github.dohex.openrose.circulation.CirculationCommand
import io.github.dohex.openrose.circulation.CirculationFailure
import io.github.dohex.openrose.circulation.CirculationReply
import io.github.dohex.openrose.circulation.CirculationResult
import io.github.dohex.openrose.circulation.HandoffStatus
import io.github.dohex.openrose.circulation.PairingReply
import io.github.dohex.openrose.circulation.PairingTicket
import io.github.dohex.openrose.circulation.PeerDirectory
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.RemoteReading
import io.github.dohex.openrose.circulation.RemoteSessionReference
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.EnumNames
import uniffi.openrose_circulation.CirculationCommand as RustCommand
import uniffi.openrose_circulation.CirculationReply as RustReply
import uniffi.openrose_circulation.HandoffStatus as RustHandoff
import uniffi.openrose_circulation.PairingOffer as RustOffer
import uniffi.openrose_circulation.RemoteReading as RustReading
import uniffi.openrose_circulation.RemoteSessionReference as RustSession
import uniffi.openrose_headset.HeadsetCommand as RustHeadsetCommand
import uniffi.openrose_headset.NoiseMode as RustNoiseMode

private val rustNoiseModes = EnumNames(RustNoiseMode.entries)
private val kotlinNoiseModes = EnumNames(NoiseMode.entries)
private val kotlinProfiles = EnumNames(ModelProfile.entries)
private val kotlinLayouts = EnumNames(BatteryLayout.entries)
private val kotlinSettings = EnumNames(DeviceSetting.entries)
private val kotlinStatuses = EnumNames(RemoteControlStatus.entries)

internal fun CirculationCommand.toRust(): RustCommand? = when (this) {
    CirculationCommand.Directory -> RustCommand.Directory
    CirculationCommand.Offer -> RustCommand.Offer
    is CirculationCommand.Pair -> RustCommand.Pair(peer, code)
    is CirculationCommand.PairQr -> RustCommand.PairQr(qr)
    is CirculationCommand.Forget -> RustCommand.Forget(peer)
    is CirculationCommand.Read -> RustCommand.Read(peer)
    is CirculationCommand.SetSetting -> command.toRust()?.let { RustCommand.Set(session.toRust(), it) }
    is CirculationCommand.Handoff -> RustCommand.Handoff(address)
    is CirculationCommand.Pull -> RustCommand.Pull(address)
}

internal fun RustReply.toClient(): CirculationReply = when (this) {
    is RustReply.Directory -> PeerDirectory(
        paired = paired.associate { it.id to it.name },
        nearby = nearby.associate { it.id to it.name },
        offer = offer?.toClient(),
    )
    is RustReply.Offer -> PairingReply(offer.toClient())
    is RustReply.Paired -> CirculationResult(PAIRED_MESSAGE)
    RustReply.Forgotten -> CirculationResult(FORGOTTEN_MESSAGE)
    is RustReply.Reading -> reading.toClient()
    is RustReply.Handoff -> status.toClient()
    is RustReply.Pulled -> CirculationResult(outcome.text())
    is RustReply.Failed -> CirculationFailure(error.text())
}

private fun HeadsetCommand.toRust(): RustHeadsetCommand? = when (this) {
    is HeadsetCommand.NoiseMode -> RustHeadsetCommand.NoiseMode(rustNoiseModes.of(mode))
    is HeadsetCommand.AncLevel -> RustHeadsetCommand.AncLevel(level)
    is HeadsetCommand.TransparencyLevel -> RustHeadsetCommand.TransparencyLevel(level)
    is HeadsetCommand.EqualizerPreset -> RustHeadsetCommand.EqualizerPreset(preset)
    is HeadsetCommand.GameMode -> RustHeadsetCommand.GameMode(enabled)
    else -> null
}

private fun RemoteSessionReference.toRust(): RustSession = RustSession(peer, device.value, token)

private fun RustSession.toClient(): RemoteSessionReference = RemoteSessionReference(peerId, DeviceId(headsetId), token)

private fun RustOffer.toClient(): PairingTicket = PairingTicket(code, qr, validForMillis.toLong())

private fun RustHandoff.toClient(): HandoffStatus = HandoffStatus(available, mediaReady, offline, progress?.text())

private fun RustReading.toClient(): RemoteReading = RemoteReading(
    status = kotlinStatuses.of(status),
    deviceName = headsetName,
    profile = profile?.let(kotlinProfiles::of),
    session = session?.toClient(),
    mode = noiseMode?.let(kotlinNoiseModes::of),
    modes = noiseModes.map(kotlinNoiseModes::of),
    ancLevel = ancLevel,
    ancLevels = ancLevels,
    transparencyLevel = transparencyLevel,
    transparencyLevels = transparencyLevels,
    eqPreset = eqPreset,
    eqPresets = eqPresets,
    gameModeSupported = gameModeSupported,
    gameMode = gameMode,
    blockedReasons = blockedReasons.entries.associate { (setting, requirement) -> kotlinSettings.of(setting) to requirement.text() },
    left = battery?.left,
    right = battery?.right,
    case = battery?.case,
    batteryLayout = battery?.layout?.let(kotlinLayouts::of),
)
