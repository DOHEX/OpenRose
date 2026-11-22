package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CancellationException

fun supportsCirculation(profile: ModelProfile?): Boolean = profile == ModelProfile.I5_ZT

/** The source device, headphones and owner-issued token observed in the same authenticated reading. */
data class RemoteSessionReference(val peer: String, val device: DeviceId, val token: String)

sealed interface CirculationCommand {
    data object Directory : CirculationCommand
    data object Offer : CirculationCommand
    data class Pair(val peer: String, val code: String) : CirculationCommand
    data class PairQr(val qr: String) : CirculationCommand
    data class Forget(val peer: String) : CirculationCommand
    data class Read(val peer: String) : CirculationCommand
    data class SetNoise(val session: RemoteSessionReference, val mode: NoiseMode) : CirculationCommand
    data class Handoff(val address: String?) : CirculationCommand
    data class Pull(val address: String) : CirculationCommand
}

sealed interface CirculationReply

data class CirculationFailure(val reason: String) : CirculationReply
data class PairingTicket(val code: String, val qr: String, val validForMillis: Long)
data class PeerDirectory(
    val paired: Map<String, String> = emptyMap(),
    val nearby: Map<String, String> = emptyMap(),
    val offer: PairingTicket? = null,
    val error: String? = null,
) : CirculationReply

data class PairingReply(val ticket: PairingTicket?, val error: String? = null) : CirculationReply
data class CirculationResult(val message: String? = null, val error: String? = null) : CirculationReply
data class HandoffStatus(
    val available: Boolean = false,
    val mediaReady: Boolean = false,
    val offline: Boolean = false,
    val progress: String? = null,
) : CirculationReply

enum class RemoteControlStatus { WAITING, IDLE, RECOVERING, INITIALIZING, READY, UNAVAILABLE }

/**
 * [batteryLayout] says which of [left], [right] and [case] the held headphones report. Null from peers that
 * predate the field; the reader then assumes [BatteryLayout.EARBUDS_AND_CASE].
 */
data class RemoteReading(
    val status: RemoteControlStatus = RemoteControlStatus.WAITING,
    val deviceName: String? = null,
    val session: RemoteSessionReference? = null,
    val mode: NoiseMode? = null,
    val modes: List<NoiseMode> = emptyList(),
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val batteryLayout: BatteryLayout? = null,
    val error: String? = null,
) : CirculationReply

/** The layout a peer sent under its [BatteryLayout.name]; null when absent or from a newer peer's unknown layout. */
fun parseBatteryLayout(name: String?): BatteryLayout? = BatteryLayout.entries.firstOrNull { it.name == name }

class CirculationClient(private val exchange: suspend (CirculationCommand) -> CirculationReply) {
    suspend fun directory(): PeerDirectory = request(CirculationCommand.Directory) { PeerDirectory(error = it) }
    suspend fun offer(): PairingReply = request(CirculationCommand.Offer) { PairingReply(null, it) }
    suspend fun pair(peer: String, code: String) = action(CirculationCommand.Pair(peer, code))
    suspend fun pairQr(qr: String) = action(CirculationCommand.PairQr(qr))
    suspend fun forget(peer: String) = action(CirculationCommand.Forget(peer))
    suspend fun pull(address: String) = action(CirculationCommand.Pull(address))
    suspend fun handoff(address: String?): HandoffStatus = request(CirculationCommand.Handoff(address)) { HandoffStatus() }
    suspend fun read(peer: String): RemoteReading = request(CirculationCommand.Read(peer), ::unavailableReading)
    suspend fun setNoise(session: RemoteSessionReference, mode: NoiseMode): RemoteReading {
        val command = CirculationCommand.SetNoise(session, mode)
        return request(command, ::unavailableReading)
    }

    private suspend fun action(command: CirculationCommand): CirculationResult = request(command) { CirculationResult(error = it) }

    @Suppress("TooGenericExceptionCaught") // Socket, Binder and platform failures become operation feedback at the calling boundary.
    private suspend inline fun <reified T : CirculationReply> request(command: CirculationCommand, failure: (String) -> T): T = try {
        when (val reply = exchange(command)) {
            is T -> reply
            is CirculationFailure -> failure(reply.reason)
            else -> failure("互联服务返回了无法识别的结果")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: Exception) {
        failure(problem.message ?: "互联服务不可用")
    }
}

private fun unavailableReading(reason: String) = RemoteReading(RemoteControlStatus.UNAVAILABLE, error = reason)
