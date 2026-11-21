package io.github.dohex.openrose.ui

import kotlinx.coroutines.CancellationException

internal data class PairingTicket(val code: String, val qr: String, val validForMillis: Long)

internal data class PeerDirectory(
    val paired: Map<String, String> = emptyMap(),
    val nearby: Map<String, String> = emptyMap(),
    val offer: PairingTicket? = null,
    val reportsOffer: Boolean = false,
    val error: String? = null,
)

internal data class PairingReply(val ticket: PairingTicket?, val error: String?)
internal data class CirculationResult(val message: String?, val error: String?)
internal data class HandoffStatus(val available: Boolean, val mediaReady: Boolean, val offline: Boolean, val progress: String?)

enum class RemoteControlStatus { WAITING, IDLE, RECOVERING, INITIALIZING, READY, UNAVAILABLE }

data class RemoteReading(
    val status: RemoteControlStatus = RemoteControlStatus.WAITING,
    val deviceName: String? = null,
    val device: String? = null,
    val session: String? = null,
    val mode: String? = null,
    val modes: List<String> = emptyList(),
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val error: String? = null,
)

/** The platform supplies the Binder or desktop dispatch. UI callers only see pairing, handoff and reading types. */
class CirculationClient(private val exchange: suspend (Map<String, String>) -> Map<String, String>) {
    internal suspend fun directory(): PeerDirectory {
        val fields = exchange.request("list")
        fun devices(prefix: String) = fields.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        return PeerDirectory(
            paired = devices("paired."),
            nearby = devices("near."),
            offer = fields.ticket(),
            reportsOffer = "offerState" in fields,
            error = fields["error"],
        )
    }

    internal suspend fun offer(): PairingReply = exchange.request("offer").let { PairingReply(it.ticket(), it["error"]) }
    internal suspend fun pair(peer: String, code: String) = exchange.request("pair", "peer" to peer, "code" to code).result()
    internal suspend fun pairQr(qr: String) = exchange.request("pairQr", "qr" to qr).result()
    internal suspend fun forget(peer: String) = exchange.request("forget", "peer" to peer).result()
    internal suspend fun read(peer: String) = exchange.request("read", "peer" to peer).reading()
    internal suspend fun setNoise(peer: String, device: String, session: String, mode: String): RemoteReading = exchange.request(
        "noise",
        "peer" to peer,
        "device" to device,
        "session" to session,
        "mode" to mode,
    ).reading()

    internal suspend fun handoff(address: String?): HandoffStatus {
        val fields = if (address == null) exchange.request("circulation") else exchange.request("circulation", "address" to address)
        return HandoffStatus(fields["available"] == "true", fields["mediaReady"] == "true", fields["offline"] == "true", fields["progress"])
    }

    internal suspend fun pull(address: String) = exchange.request("pull", "address" to address).result()
    internal suspend fun output(): String? = exchange.request("output")["output"]
    internal suspend fun openSoundSettings() = exchange.request("soundSettings").result()
}

private typealias CirculationExchange = suspend (Map<String, String>) -> Map<String, String>

@Suppress("TooGenericExceptionCaught") // Socket and Binder failures are reported at the platform boundary.
private suspend fun CirculationExchange.request(op: String, vararg fields: Pair<String, String>): Map<String, String> = try {
    invoke(mapOf("op" to op, *fields))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    mapOf("error" to (failure.localizedMessage ?: "互联服务不可用"))
}

private fun Map<String, String>.result() = CirculationResult(get("message"), get("error"))

private fun Map<String, String>.ticket(): PairingTicket? {
    val code = get("code") ?: return null
    val qr = get("qr") ?: return null
    // Hosts predating offer-state publication used the same two-minute lifetime.
    return PairingTicket(code, qr, get("validForMillis")?.toLongOrNull() ?: 120_000L)
}

private fun Map<String, String>.reading(): RemoteReading {
    val status = when (get("status")) {
        "initializing" -> RemoteControlStatus.INITIALIZING
        "ready" -> RemoteControlStatus.READY
        "recovering" -> RemoteControlStatus.RECOVERING
        "idle" -> RemoteControlStatus.IDLE
        else -> if ("error" in this) RemoteControlStatus.UNAVAILABLE else RemoteControlStatus.WAITING
    }
    fun battery(key: String) = get(key)?.toIntOrNull()?.takeIf { it in 0..100 }
    return RemoteReading(
        status = status,
        deviceName = get("deviceName"),
        device = get("device"),
        session = get("session"),
        mode = get("mode"),
        modes = get("modes").orEmpty().split(',').filter(String::isNotBlank),
        left = battery("left"),
        right = battery("right"),
        case = battery("case"),
        error = get("error"),
    )
}
