package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import java.util.UUID

/** Read by a paired peer. Only an i5 with both current system audio and a ready control session is advertised. */
class HeadsetEndpoint(private val control: ControlRuntime) {
    private val incarnation = UUID.randomUUID().toString()

    fun handle(message: Map<String, String>): Map<String, String> = when (message["op"]) {
        "read" -> read()
        else -> mapOf("error" to "未知的耳机操作")
    }

    private fun read(): Map<String, String> {
        val state = control.state.value
        val session = state.readySession
        val device = state.device
        if (device == null || session == null || (state.connection as? ConnectionState.Connected)?.phase != ConnectionPhase.READY ||
            device.profile != ModelProfile.I5_ZT ||
            state.audioDeviceId?.value !in listOfNotNull(device.deviceId.value, device.classicAddress, state.connectionTarget?.classicAddress)
        ) {
            return mapOf("status" to "idle")
        }
        fun confirmed(field: DeviceField) = (device.fields[field] as? FieldReading.Known)?.value
        return buildMap {
            put("status", "ready")
            put("device", device.deviceId.value)
            put("deviceName", device.profile.displayName)
            put("session", "$incarnation/${session.sessionId}")
            val parts = mapOf("left" to DeviceField.LEFT_BATTERY, "right" to DeviceField.RIGHT_BATTERY, "case" to DeviceField.CASE_BATTERY)
            parts.forEach { (name, field) ->
                (confirmed(field) as? FieldValue.Number)?.value?.takeIf { it in 0..100 }?.let { put(name, it.toString()) }
            }
            (confirmed(DeviceField.NOISE_MODE) as? FieldValue.Text)?.value?.let { put("mode", it) }
            device.capabilities.noiseControl?.modes?.let { put("modes", it.joinToString(",")) }
        }
    }
}

/** UI-only dispatch. Network peers are routed separately to [HeadsetEndpoint.handle]. */
fun CirculationNode.localCommand(fields: Map<String, String>): Map<String, String> = when (fields["op"]) {
    "list" -> buildMap {
        peers.value.forEach { put("paired.${it.id}", it.name) }
        nearby.value.forEach { put("near.${it.id}", it.name) }
    }
    "offer" -> offerPairing().let { mapOf("code" to it.code, "qr" to it.qr) }
    "pair" -> {
        pair(fields.getValue("peer"), fields.getValue("code"))
        mapOf("message" to "已配对")
    }
    "pairQr" -> {
        pairQr(fields.getValue("qr"))
        mapOf("message" to "已配对")
    }
    "forget" -> {
        forget(fields.getValue("peer"))
        mapOf("message" to "已解除配对")
    }
    "read" -> request(fields.getValue("peer"), mapOf("op" to "read"))
    else -> mapOf("error" to "未知的互联操作")
}
