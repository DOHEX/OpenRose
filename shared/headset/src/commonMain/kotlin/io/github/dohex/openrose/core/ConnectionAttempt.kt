package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolCodec
import io.github.dohex.openrose.core.protocol.ProtocolReply
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeMark

/** One connection attempt for a recognized model, over the transport that admitted it. */
internal class ConnectionAttempt(
    val id: Long,
    val candidate: DeviceCandidate,
    val profile: ModelProfile,
    val transport: ControlTransport,
    val forceBle: Boolean,
) {
    lateinit var task: Deferred<OperationResult>
    var connection: ControlConnection? = null
    var observer: Job? = null
    lateinit var codec: ProtocolCodec
    lateinit var progress: MutableStateFlow<ReadProgress>
    val linkEpoch = MutableStateFlow(0L)
    var initializationQueryIndex: Int? = null
    var settings = SettingQueue.Pending()
    var nextWriteAt: TimeMark? = null
    var hasWritten = false
    var ready = false
}

/** A fresh device session: capabilities before any firmware read, and every readable field still loading. */
internal fun ConnectionAttempt.loadingDevice(): DeviceState {
    val capabilities = profile.capabilities()
    return DeviceState(
        candidate.deviceId,
        id,
        profile,
        capabilities,
        capabilities.readableFields.associateWith { FieldReading.Loading }.toPersistentMap(),
        classicAddress = candidate.classicAddress,
    )
}

internal data class ReadProgress(
    val keys: PersistentSet<String> = persistentSetOf(),
    val meaningful: Boolean = false,
    val ended: Boolean = false,
    val keyRevisions: PersistentMap<String, Long> = persistentMapOf(),
    val fieldRevisions: PersistentMap<DeviceField, Long> = persistentMapOf(),
    val failure: Throwable? = null,
) {
    fun received(replies: List<ProtocolReply>): ReadProgress {
        val receivedKeys = replies.flatMap { it.responseKeys }.toSet()
        val fields = replies.flatMap { it.readings.keys }.toSet()
        return copy(
            keys = keys.addingAll(receivedKeys),
            meaningful = meaningful || replies.any { it.accepted },
            keyRevisions = keyRevisions.mutate { revisions ->
                receivedKeys.forEach { revisions[it] = (revisions[it] ?: 0) + 1 }
            },
            fieldRevisions = fieldRevisions.mutate { revisions ->
                fields.forEach { revisions[it] = (revisions[it] ?: 0) + 1 }
            },
        )
    }
}

internal class LinkEnded : Exception("设备连接已结束")

internal fun ConnectionAttempt.phase(state: MutableStateFlow<ControlState>, epoch: Long, phase: ConnectionPhase) = update(state, epoch) {
    val current = it.connection as? ConnectionState.Connecting
    if (current != null && phase != ConnectionPhase.READY && phase.ordinal >= current.phase.ordinal) {
        it.copy(connection = current.copy(phase = phase))
    } else {
        it
    }
}

internal fun ConnectionAttempt.update(state: MutableStateFlow<ControlState>, epoch: Long? = null, update: (ControlState) -> ControlState) {
    state.update {
        val live = (epoch == null || linkEpoch.value == epoch) && matches(it)
        if (live) update(it) else it
    }
}

internal fun ConnectionAttempt.matches(state: ControlState): Boolean {
    val currentSession = state.device?.sessionId
    return currentSession == id && state.connection !is ConnectionState.Disconnected
}

internal fun ConnectionAttempt.receiveNotification(state: MutableStateFlow<ControlState>, bytes: ByteArray) {
    val replies = codec.feedNotification(bytes.copyOf())
    var current = state.value
    while (matches(current)) {
        var device = requireNotNull(current.device)
        val controlReplies = replies.map { reply ->
            device = device.withReadings(reply.readings)
            reply.forReadableFields(device.capabilities.readableFields)
        }
        val diagnostics = device.diagnostics
        val observed = device.copy(
            diagnostics = diagnostics.copy(
                rawNotifications = diagnostics.rawNotifications.appendRecent(listOf(bytes.hex())),
                unknownNotifications = diagnostics.unknownNotifications.appendRecent(
                    controlReplies.filter { !it.accepted }.map { it.rawHex },
                ),
                warnings = diagnostics.warnings.appendRecent(replies.flatMap { it.warnings }),
            ),
        )
        // Progress belongs to the committed observations, including when a concurrent timeout changes field errors.
        if (state.compareAndSet(current, current.copy(device = observed))) {
            progress.update { it.received(controlReplies) }
            break
        }
        current = state.value
    }
}

private fun PersistentList<String>.appendRecent(values: Iterable<String>): PersistentList<String> = mutate {
    it.addAll(values)
    while (it.size > 128) it.removeAt(0)
}

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** A decoder shared by several models can return diagnostic slots that cannot advance this model's control. */
private fun ProtocolReply.forReadableFields(fields: Set<DeviceField>): ProtocolReply {
    val current = readings.filterKeys { it in fields }
    if (current.size == readings.size) return this
    return ProtocolReply(rawHex, if (current.isEmpty()) emptySet() else responseKeys, current, warnings)
}
