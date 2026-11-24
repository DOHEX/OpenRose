package io.github.dohex.openrose.bridge

import android.os.Bundle
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.transport.DeviceId

fun CirculationCommand.toBundle(): Bundle = Bundle().apply {
    val operation = when (val command = this@toBundle) {
        CirculationCommand.Directory -> "list"

        CirculationCommand.Offer -> "offer"

        is CirculationCommand.Pair -> {
            putString("peer", command.peer)
            putString("code", command.code)
            "pair"
        }

        is CirculationCommand.PairQr -> {
            putString("qr", command.qr)
            "pairQr"
        }

        is CirculationCommand.Forget -> {
            putString("peer", command.peer)
            "forget"
        }

        is CirculationCommand.Read -> {
            putString("peer", command.peer)
            "read"
        }

        is CirculationCommand.SetSetting -> {
            putSession(command.session)
            putString("setting", command.command.setting.name)
            putString("value", requireNotNull(encodeRemoteValue(command.command)) { "不支持远程设置 ${command.command.setting.name}" })
            "set"
        }

        is CirculationCommand.Handoff -> {
            putString("address", command.address)
            "handoff"
        }

        is CirculationCommand.Pull -> {
            putString("address", command.address)
            "pull"
        }
    }
    putString("op", operation)
}

fun Bundle.circulationCommand(): CirculationCommand = when (required("op")) {
    "list" -> CirculationCommand.Directory

    "offer" -> CirculationCommand.Offer

    "pair" -> CirculationCommand.Pair(required("peer"), required("code"))

    "pairQr" -> CirculationCommand.PairQr(required("qr"))

    "forget" -> CirculationCommand.Forget(required("peer"))

    "read" -> CirculationCommand.Read(required("peer"))

    "set" -> CirculationCommand.SetSetting(
        session(),
        requireNotNull(decodeRemoteCommand(required("setting"), required("value"))) { "无效的远程设置" },
    )

    "handoff" -> CirculationCommand.Handoff(getString("address"))

    "pull" -> CirculationCommand.Pull(required("address"))

    else -> throw IllegalArgumentException("未知的互联操作")
}

fun CirculationReply.toBundle(): Bundle = Bundle().apply {
    when (val reply = this@toBundle) {
        is CirculationFailure -> putString("error", reply.reason)

        is CirculationResult -> {
            putString("message", reply.message)
            putString("error", reply.error)
        }

        is PeerDirectory -> {
            putBundle("paired", reply.paired.peerBundle())
            putBundle("nearby", reply.nearby.peerBundle())
            putTicket(reply.offer)
            putString("error", reply.error)
        }

        is PairingReply -> {
            putTicket(reply.ticket)
            putString("error", reply.error)
        }

        is HandoffStatus -> {
            putBoolean("available", reply.available)
            putBoolean("mediaReady", reply.mediaReady)
            putBoolean("offline", reply.offline)
            putString("progress", reply.progress)
        }

        is RemoteReading -> putRemoteReading(reply)
    }
}

fun Bundle.circulationReply(command: CirculationCommand): CirculationReply {
    getString("error")?.let { return CirculationFailure(it) }
    val battery: (String) -> Int? = { key -> if (containsKey(key)) getInt(key).takeIf { it in 0..100 } else null }
    val ticket = getString("code")?.let { PairingTicket(it, required("qr"), getLong("validForMillis")) }
    return when (command) {
        CirculationCommand.Directory -> PeerDirectory(peers("paired"), peers("nearby"), ticket)

        CirculationCommand.Offer -> PairingReply(ticket)

        is CirculationCommand.Read, is CirculationCommand.SetSetting -> RemoteReading(
            status = RemoteControlStatus.valueOf(required("status")),
            deviceName = getString("name"),
            profile = parseModelProfile(getString("profile")),
            session = getString("session")?.let { session() },
            mode = getString("mode")?.let(NoiseMode::fromId),
            modes = getStringArrayList("modes").orEmpty().mapNotNull(NoiseMode::fromId),
            ancLevel = if (containsKey("ancLevel")) getInt("ancLevel") else null,
            ancLevels = getIntegerArrayList("ancLevels").orEmpty(),
            transparencyLevel = if (containsKey("transparencyLevel")) getInt("transparencyLevel") else null,
            transparencyLevels = getIntegerArrayList("transparencyLevels").orEmpty(),
            eqPreset = getString("eqPreset"),
            eqPresets = getStringArrayList("eqPresets").orEmpty(),
            gameModeSupported = getBoolean("gameModeSupported"),
            gameMode = if (containsKey("gameMode")) getBoolean("gameMode") else null,
            blockedReasons = peers("blocked").mapNotNull { (key, reason) -> parseDeviceSetting(key)?.let { it to reason } }.toMap(),
            left = battery("left"),
            right = battery("right"),
            case = battery("case"),
            batteryLayout = parseBatteryLayout(getString("batteryLayout")),
        )

        is CirculationCommand.Handoff -> HandoffStatus(
            getBoolean("available"),
            getBoolean("mediaReady"),
            getBoolean("offline"),
            getString("progress"),
        )

        is CirculationCommand.Pair, is CirculationCommand.PairQr, is CirculationCommand.Forget,
        is CirculationCommand.Pull,
        -> CirculationResult(getString("message"))
    }
}

private fun Bundle.required(key: String): String = requireNotNull(getString(key)) { "缺少互联字段 $key" }

private fun Bundle.putSession(reference: RemoteSessionReference) {
    putString("peer", reference.peer)
    putString("device", reference.device.value)
    putString("session", reference.token)
}

private fun Bundle.session() = RemoteSessionReference(required("peer"), DeviceId(required("device")), required("session"))

private fun Map<String, String>.peerBundle(): Bundle = Bundle().also { data -> forEach { (id, name) -> data.putString(id, name) } }
private fun Bundle.peers(key: String): Map<String, String> {
    val data = getBundle(key) ?: return emptyMap()
    return data.keySet().associateWith { data.required(it) }
}

private fun Bundle.putTicket(ticket: PairingTicket?) {
    if (ticket != null) {
        putString("code", ticket.code)
        putString("qr", ticket.qr)
        putLong("validForMillis", ticket.validForMillis)
    }
}

private fun Bundle.putRemoteReading(reply: RemoteReading) {
    putString("status", reply.status.name)
    putString("name", reply.deviceName)
    putString("profile", reply.profile?.name)
    reply.session?.let(::putSession)
    putString("mode", reply.mode?.id)
    putStringArrayList("modes", ArrayList(reply.modes.map(NoiseMode::id)))
    reply.ancLevel?.let { putInt("ancLevel", it) }
    putIntegerArrayList("ancLevels", ArrayList(reply.ancLevels))
    reply.transparencyLevel?.let { putInt("transparencyLevel", it) }
    putIntegerArrayList("transparencyLevels", ArrayList(reply.transparencyLevels))
    putString("eqPreset", reply.eqPreset)
    putStringArrayList("eqPresets", ArrayList(reply.eqPresets))
    putBoolean("gameModeSupported", reply.gameModeSupported)
    reply.gameMode?.let { putBoolean("gameMode", it) }
    if (reply.blockedReasons.isNotEmpty()) {
        putBundle("blocked", reply.blockedReasons.mapKeys { it.key.name }.peerBundle())
    }
    reply.left?.let { putInt("left", it) }
    reply.right?.let { putInt("right", it) }
    reply.case?.let { putInt("case", it) }
    putString("batteryLayout", reply.batteryLayout?.name)
    putString("error", reply.error)
}
