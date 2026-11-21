package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolReply
import kotlinx.collections.immutable.toPersistentMap

/** A shared decoder can return diagnostic values for slots absent from this model. Only model fields advance control. */
internal class ConnectionReadings(private val profile: ModelProfile) {
    private val observed = mutableMapOf<DeviceField, FieldReading>()
    var capabilities = profile.capabilities()
        private set

    fun receive(reply: ProtocolReply): ProtocolReply {
        val previousGame = observed[DeviceField.GAME_MODE]
        observed.putAll(reply.readings)
        val game = observed[DeviceField.GAME_MODE]
        if (profile.invalidatesEqualizer(previousGame, game, reply.readings.keys)) {
            observed.remove(DeviceField.EQ)
        }
        if (DeviceField.GAME_MODE in reply.readings || DeviceField.FIRMWARE in reply.readings) {
            capabilities = profile.capabilities(observed)
        }
        val current = reply.readings.filterKeys { it in capabilities.readableFields }
        return if (current.size == reply.readings.size) {
            reply
        } else {
            ProtocolReply(reply.rawHex, if (current.isEmpty()) emptySet() else reply.responseKeys, current, reply.warnings)
        }
    }

    // Keep decoded fields for diagnostics, including values arriving before the firmware that unlocks their control.
    fun published() = (capabilities.readableFields.associateWith { FieldReading.Loading } + observed).toPersistentMap()
}
