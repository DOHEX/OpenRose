package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.transport.DeviceId

fun CirculationNode.localCommand(command: CirculationCommand): CirculationReply = when (command) {
    CirculationCommand.Directory -> PeerDirectory(
        paired = peers.value.associate { it.id to it.name },
        nearby = nearby.value.associate { it.id to it.name },
        offer = currentOffer(),
    )

    CirculationCommand.Offer -> PairingReply(offerPairing())

    is CirculationCommand.Pair -> {
        pair(command.peer, command.code)
        CirculationResult("已配对")
    }

    is CirculationCommand.PairQr -> {
        pairQr(command.qr)
        CirculationResult("已配对")
    }

    is CirculationCommand.Forget -> {
        forget(command.peer)
        CirculationResult("已解除配对")
    }

    is CirculationCommand.Handoff -> {
        val paired = peers.value
        HandoffStatus(
            available = circulation != null && paired.isNotEmpty(),
            mediaReady = command.address?.let { circulation?.isMediaConnected(it) } == true,
            offline = paired.isNotEmpty() && paired.none { peer -> nearby.value.any { it.id == peer.id } },
            progress = circulation?.progress?.takeIf(String::isNotEmpty),
        )
    }

    is CirculationCommand.Pull -> circulation?.pull(this, command.address) ?: CirculationResult(error = "该平台暂不支持流转")

    is CirculationCommand.Read -> request(command.peer, mapOf("op" to "read")).remoteReading(command.peer)

    is CirculationCommand.SetNoise -> {
        val reference = command.session
        val message = mapOf("op" to "noise", "device" to reference.device.value, "session" to reference.token, "mode" to command.mode.id)
        request(reference.peer, message).remoteReading(reference.peer)
    }
}

private fun Map<String, String>.remoteReading(peer: String): RemoteReading {
    val status = when (get("status")) {
        "initializing" -> RemoteControlStatus.INITIALIZING
        "ready" -> RemoteControlStatus.READY
        "recovering" -> RemoteControlStatus.RECOVERING
        "idle" -> RemoteControlStatus.IDLE
        else -> if ("error" in this) RemoteControlStatus.UNAVAILABLE else RemoteControlStatus.WAITING
    }
    val device = get("device")
    val token = get("session")
    fun battery(key: String) = get(key)?.toIntOrNull()?.takeIf { it in 0..100 }
    return RemoteReading(
        status = status,
        deviceName = get("deviceName"),
        session = if (device != null && token != null) RemoteSessionReference(peer, DeviceId(device), token) else null,
        mode = get("mode")?.let(NoiseMode::fromId),
        modes = get("modes").orEmpty().split(',').mapNotNull(NoiseMode::fromId),
        left = battery("left"),
        right = battery("right"),
        case = battery("case"),
        batteryLayout = parseBatteryLayout(get("batteryLayout")),
        error = get("error"),
    )
}
