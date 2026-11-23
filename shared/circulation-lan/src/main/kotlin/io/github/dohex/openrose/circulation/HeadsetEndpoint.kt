package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.validate
import kotlinx.coroutines.runBlocking
import java.util.UUID

class HeadsetEndpoint(private val control: ControlRuntime, private val ownerActive: () -> Boolean = { true }) {
    private val incarnation = UUID.randomUUID().toString()

    internal fun handle(message: Map<String, String>): Map<String, String> {
        if (!ownerActive()) return mapOf("error" to "控制所有者已结束")
        return when (message["op"]) {
            "read" -> read(control.state.value)
            "noise" -> noise(message)
            else -> mapOf("error" to "未知的耳机操作")
        }
    }

    private class Held(val session: SessionReference, val device: DeviceState)

    private fun held(state: ControlState): Held? {
        val device = state.device?.takeIf { supportsCirculation(it.profile) && state.deviceHasSystemAudio } ?: return null
        return state.noiseModeSession?.let { Held(it, device) }
    }

    private fun noise(message: Map<String, String>): Map<String, String> {
        val state = control.state.value
        val held = held(state)
        val session = held?.session
        if (session == null || message["device"] != session.deviceId.value || message["session"] != token(session)) return expired()
        val command = message["mode"]?.let(NoiseMode::fromId)?.let { HeadsetCommand.NoiseMode(it) }
        if (command == null || held.device.validate(command) != null) {
            return mapOf("error" to "耳机不支持该降噪模式")
        }
        val result = runBlocking { control.set(command, expectedSessionId = session.sessionId) }
        if (result is OperationResult.Failure) return mapOf("error" to "耳机未确认 · ${result.error.message}")
        val current = control.state.value
        if (held(current)?.session != session) return expired()
        return read(current)
    }

    private fun expired() = mapOf("error" to "耳机会话已结束，请重新读取")

    private fun token(session: SessionReference) = "$incarnation/${session.sessionId}"

    private fun audioOnly(state: ControlState): Map<String, String> {
        val id = state.audioDeviceId ?: return mapOf("status" to "idle")
        val candidates = listOfNotNull(state.connectionTarget) + state.bondedDevices + state.candidates
        val held = candidates.firstOrNull {
            supportsCirculation(it.profile) && it.matchesAudioDevice(id)
        }
        return held?.profile?.let { mapOf("status" to "recovering", "deviceName" to it.displayName) } ?: mapOf("status" to "idle")
    }

    private fun read(state: ControlState): Map<String, String> {
        val held = held(state) ?: return audioOnly(state)
        val session = held.session
        val device = held.device
        return buildMap {
            put("status", if (state.readySession == session) "ready" else "initializing")
            put("device", device.deviceId.value)
            put("deviceName", device.profile.displayName)
            put("session", token(session))
            val parts = mapOf("left" to DeviceField.LEFT_BATTERY, "right" to DeviceField.RIGHT_BATTERY, "case" to DeviceField.CASE_BATTERY)
            parts.forEach { (name, field) ->
                device.confirmedNumber(field)?.let { put(name, it.toString()) }
            }
            put("batteryLayout", device.profile.batteryLayout.name)
            device.confirmedText(DeviceField.NOISE_MODE)?.let { put("mode", it) }
            device.capabilities.noiseControl?.modes?.let { put("modes", it.joinToString(",")) }
        }
    }
}
