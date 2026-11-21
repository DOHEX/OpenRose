package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Read by a paired peer. Only an i5 with both current system audio and a ready control session is advertised. */
class HeadsetEndpoint(private val control: ControlRuntime) {
    private val incarnation = UUID.randomUUID().toString()

    fun handle(message: Map<String, String>): Map<String, String> = when (message["op"]) {
        "read" -> read(control.state.value)
        "noise" -> noise(message)
        else -> mapOf("error" to "未知的耳机操作")
    }

    private fun heldSession(state: ControlState): SessionReference? {
        val device = state.device ?: return null
        if (device.profile != ModelProfile.I5_ZT) return null
        if ((state.connection as? ConnectionState.Connected)?.phase != ConnectionPhase.READY) return null
        val audio = state.audioDeviceId?.value ?: return null
        val identities = listOfNotNull(device.deviceId.value, device.classicAddress, state.connectionTarget?.classicAddress)
        return state.readySession?.takeIf { audio in identities }
    }

    private fun noise(message: Map<String, String>): Map<String, String> {
        val state = control.state.value
        val session = heldSession(state)
        val mode = message["mode"]
        if (session == null || message["device"] != session.deviceId.value || message["session"] != token(session)) return expired()
        if (mode == null || mode !in state.device?.capabilities?.noiseControl?.modes.orEmpty()) {
            return mapOf("error" to "耳机不支持该降噪模式")
        }
        val result = runBlocking { control.set(DeviceSetting.NOISE_MODE, FieldValue.Text(mode), expectedSessionId = session.sessionId) }
        if (result is OperationResult.Failure) return mapOf("error" to "耳机未确认 · ${result.error.message}")
        val current = control.state.value
        if (heldSession(current) != session) return expired()
        return read(current)
    }

    private fun expired() = mapOf("error" to "耳机会话已结束，请重新读取")

    private fun token(session: SessionReference) = "$incarnation/${session.sessionId}"

    private fun audioOnly(state: ControlState): Map<String, String> {
        val id = state.audioDeviceId ?: return mapOf("status" to "idle")
        val candidates = listOfNotNull(state.connectionTarget) + state.bondedDevices + state.candidates
        val held = candidates.any {
            it.profile == ModelProfile.I5_ZT && (it.deviceId == id || it.classicAddress == id.value)
        }
        return if (held) mapOf("status" to "recovering", "deviceName" to ModelProfile.I5_ZT.displayName) else mapOf("status" to "idle")
    }

    private fun read(state: ControlState): Map<String, String> {
        val session = heldSession(state) ?: return audioOnly(state)
        val device = requireNotNull(state.device)
        fun confirmed(field: DeviceField) = (device.fields[field] as? FieldReading.Known)?.value
        return buildMap {
            put("status", "ready")
            put("device", device.deviceId.value)
            put("deviceName", device.profile.displayName)
            put("session", token(session))
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
    "pull" -> circulation?.pull(this, fields.getValue("address")) ?: mapOf("error" to "该平台暂不支持流转")

    "circulation" -> circulationStatus(fields["address"])

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

    "read", "noise" -> request(fields.getValue("peer"), fields - "peer")

    else -> mapOf("error" to "未知的互联操作")
}

private fun CirculationNode.circulationStatus(address: String?): Map<String, String> = buildMap {
    if (address != null) put("mediaReady", (circulation?.isMediaConnected(address) == true).toString())
    if (circulation != null && peers.value.isNotEmpty()) put("available", "true")
    if (peers.value.isNotEmpty() && peers.value.none { paired -> nearby.value.any { it.id == paired.id } }) put("offline", "true")
    circulation?.progress?.takeIf(String::isNotEmpty)?.let { put("progress", it) }
}
