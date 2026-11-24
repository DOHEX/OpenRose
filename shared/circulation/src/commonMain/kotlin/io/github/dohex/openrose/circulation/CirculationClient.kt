package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CancellationException

fun supportsCirculation(profile: ModelProfile?): Boolean = profile != null

val REMOTE_CONTROL_SETTINGS: Set<DeviceSetting> = setOf(
    DeviceSetting.NOISE_MODE,
    DeviceSetting.ANC_LEVEL,
    DeviceSetting.TRANSPARENCY_LEVEL,
    DeviceSetting.EQ,
    DeviceSetting.GAME_MODE,
)

fun encodeRemoteValue(command: HeadsetCommand): String? = when (val value = command.value) {
    is FieldValue.Text -> value.value
    is FieldValue.Number -> value.value.toString()
    is FieldValue.Toggle -> value.value.toString()
    else -> null
}

fun decodeRemoteCommand(settingName: String?, rawValue: String?): HeadsetCommand? {
    val setting = DeviceSetting.entries.firstOrNull { it.name == settingName } ?: return null
    if (setting !in REMOTE_CONTROL_SETTINGS || rawValue == null) return null
    val fieldValue: FieldValue = when (setting) {
        DeviceSetting.NOISE_MODE, DeviceSetting.EQ -> FieldValue.Text(rawValue)
        DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL -> rawValue.toIntOrNull()?.let(FieldValue::Number) ?: return null
        DeviceSetting.GAME_MODE -> rawValue.toBooleanStrictOrNull()?.let(FieldValue::Toggle) ?: return null
        else -> return null
    }
    return HeadsetCommand.decode(setting, fieldValue)
}

data class RemoteSessionReference(val peer: String, val device: DeviceId, val token: String)

sealed interface CirculationCommand {
    data object Directory : CirculationCommand
    data object Offer : CirculationCommand
    data class Pair(val peer: String, val code: String) : CirculationCommand
    data class PairQr(val qr: String) : CirculationCommand
    data class Forget(val peer: String) : CirculationCommand
    data class Read(val peer: String) : CirculationCommand
    data class SetSetting(val session: RemoteSessionReference, val command: HeadsetCommand) : CirculationCommand
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

data class RemoteReading(
    val status: RemoteControlStatus = RemoteControlStatus.WAITING,
    val deviceName: String? = null,
    val profile: ModelProfile? = null,
    val session: RemoteSessionReference? = null,
    val mode: NoiseMode? = null,
    val modes: List<NoiseMode> = emptyList(),
    val ancLevel: Int? = null,
    val ancLevels: List<Int> = emptyList(),
    val transparencyLevel: Int? = null,
    val transparencyLevels: List<Int> = emptyList(),
    val eqPreset: String? = null,
    val eqPresets: List<String> = emptyList(),
    val gameModeSupported: Boolean = false,
    val gameMode: Boolean? = null,
    val blockedReasons: Map<DeviceSetting, String> = emptyMap(),
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val batteryLayout: BatteryLayout? = null,
    val error: String? = null,
) : CirculationReply

fun parseBatteryLayout(name: String?): BatteryLayout? = BatteryLayout.entries.firstOrNull { it.name == name }

fun parseModelProfile(name: String?): ModelProfile? = ModelProfile.entries.firstOrNull { it.name == name }

fun parseDeviceSetting(name: String?): DeviceSetting? = DeviceSetting.entries.firstOrNull { it.name == name }

class CirculationClient(private val exchange: suspend (CirculationCommand) -> CirculationReply) {
    suspend fun directory(): PeerDirectory = request(CirculationCommand.Directory) { PeerDirectory(error = it) }
    suspend fun offer(): PairingReply = request(CirculationCommand.Offer) { PairingReply(null, it) }
    suspend fun pair(peer: String, code: String) = action(CirculationCommand.Pair(peer, code))
    suspend fun pairQr(qr: String) = action(CirculationCommand.PairQr(qr))
    suspend fun forget(peer: String) = action(CirculationCommand.Forget(peer))
    suspend fun pull(address: String) = action(CirculationCommand.Pull(address))
    suspend fun handoff(address: String?): HandoffStatus = request(CirculationCommand.Handoff(address)) { HandoffStatus() }
    suspend fun read(peer: String): RemoteReading = request(CirculationCommand.Read(peer), ::unavailableReading)
    suspend fun setSetting(session: RemoteSessionReference, command: HeadsetCommand): RemoteReading {
        val requestCommand = CirculationCommand.SetSetting(session, command)
        return request(requestCommand, ::unavailableReading)
    }

    private suspend fun action(command: CirculationCommand): CirculationResult = request(command) { CirculationResult(error = it) }

    @Suppress("TooGenericExceptionCaught")
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
