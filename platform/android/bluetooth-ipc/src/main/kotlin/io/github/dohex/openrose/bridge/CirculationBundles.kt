package io.github.dohex.openrose.bridge

import android.os.Bundle
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.transport.DeviceId

/** Primitive Binder payloads keep both processes independent of one another's class loader. */
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

        is CirculationCommand.SetNoise -> {
            putSession(command.session)
            putString("mode", command.mode.id)
            "noise"
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
    "noise" -> CirculationCommand.SetNoise(session(), requireNotNull(NoiseMode.fromId(required("mode"))))
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

        is RemoteReading -> {
            putString("status", reply.status.name)
            putString("name", reply.deviceName)
            reply.session?.let(::putSession)
            putString("mode", reply.mode?.id)
            putStringArrayList("modes", ArrayList(reply.modes.map(NoiseMode::id)))
            reply.left?.let { putInt("left", it) }
            reply.right?.let { putInt("right", it) }
            reply.case?.let { putInt("case", it) }
            putString("error", reply.error)
        }
    }
}

fun Bundle.circulationReply(command: CirculationCommand): CirculationReply {
    getString("error")?.let { return CirculationFailure(it) }
    fun battery(key: String) = if (containsKey(key)) getInt(key).takeIf { it in 0..100 } else null
    return when (command) {
        CirculationCommand.Directory -> PeerDirectory(peers("paired"), peers("nearby"), ticket())

        CirculationCommand.Offer -> PairingReply(ticket())

        is CirculationCommand.Read, is CirculationCommand.SetNoise -> RemoteReading(
            status = RemoteControlStatus.valueOf(required("status")),
            deviceName = getString("name"),
            session = getString("session")?.let { session() },
            mode = getString("mode")?.let(NoiseMode::fromId),
            modes = getStringArrayList("modes").orEmpty().mapNotNull(NoiseMode::fromId),
            left = battery("left"),
            right = battery("right"),
            case = battery("case"),
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

private fun Bundle.ticket(): PairingTicket? = getString("code")?.let { PairingTicket(it, required("qr"), getLong("validForMillis")) }
