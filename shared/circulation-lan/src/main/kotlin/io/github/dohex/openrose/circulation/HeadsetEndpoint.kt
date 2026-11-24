package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.settingPrerequisite
import io.github.dohex.openrose.core.validate
import io.github.dohex.openrose.core.writablePresets
import kotlinx.coroutines.runBlocking
import java.util.UUID

class HeadsetEndpoint(private val control: ControlRuntime, private val ownerActive: () -> Boolean = { true }) {
    private val incarnation = UUID.randomUUID().toString()

    internal fun handle(message: Map<String, String>): Map<String, String> {
        if (!ownerActive()) return mapOf("error" to "控制所有者已结束")
        return when (message["op"]) {
            "read" -> read(control.state.value)
            "set" -> set(message)
            else -> mapOf("error" to "未知的耳机操作")
        }
    }

    private class Held(val session: SessionReference, val device: DeviceState)

    private fun held(state: ControlState): Held? {
        val device = state.device?.takeIf { supportsCirculation(it.profile) && state.deviceHasSystemAudio } ?: return null
        return state.noiseModeSession?.let { Held(it, device) }
    }

    private fun set(message: Map<String, String>): Map<String, String> {
        val state = control.state.value
        val held = held(state)
        val session = held?.session
        val command = decodeRemoteCommand(message["setting"], message["value"])
        val rejection = when {
            session == null || message["device"] != session.deviceId.value || message["session"] != token(session) -> expired()
            command == null -> mapOf("error" to "耳机不支持该设置")
            state.sessionFor(command.setting) != session -> mapOf("error" to "控制会话尚未就绪，暂不能设置")
            else -> held.device.validate(command)?.let { mapOf("error" to it.message) }
        }
        if (rejection != null) return rejection
        val result = runBlocking { control.set(requireNotNull(command), expectedSessionId = requireNotNull(session).sessionId) }
        val current = control.state.value
        return when {
            result is OperationResult.Failure -> mapOf("error" to "耳机未确认 · ${result.error.message}")
            held(current)?.session != session -> expired()
            else -> read(current)
        }
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
        val ready = state.readySession == session
        val writable = device.capabilities.writableSettings
        val noiseControl = device.capabilities.noiseControl
        val confirmedMode = device.confirmedText(DeviceField.NOISE_MODE)
        return buildMap {
            put("status", if (ready) "ready" else "initializing")
            put("device", device.deviceId.value)
            put("deviceName", device.profile.displayName)
            put("profile", device.profile.name)
            put("session", token(session))
            val parts = mapOf("left" to DeviceField.LEFT_BATTERY, "right" to DeviceField.RIGHT_BATTERY, "case" to DeviceField.CASE_BATTERY)
            parts.forEach { (name, field) ->
                device.confirmedNumber(field)?.let { put(name, it.toString()) }
            }
            put("batteryLayout", device.profile.batteryLayout.name)
            confirmedMode?.let { put("mode", it) }
            if (DeviceSetting.NOISE_MODE in writable) {
                noiseControl?.modes?.takeIf { it.isNotEmpty() }?.let { put("modes", it.joinToString(",")) }
            }
            if (ready) {
                putReadySettings(device, writable, confirmedMode)
            }
        }
    }

    private fun MutableMap<String, String>.putReadySettings(
        device: DeviceState,
        writable: Set<DeviceSetting>,
        confirmedMode: String?,
    ) {
        val noiseControl = device.capabilities.noiseControl
        val ancLevel = noiseControl?.ancLevel
        if (DeviceSetting.ANC_LEVEL in writable && ancLevel?.isVisibleIn(confirmedMode) == true) {
            put("ancLevels", ancLevel.levels.sorted().joinToString(","))
            device.confirmedNumber(DeviceField.ANC_LEVEL)?.let { put("ancLevel", it.toString()) }
        }
        val transparencyLevel = noiseControl?.transparencyLevel
        if (DeviceSetting.TRANSPARENCY_LEVEL in writable && transparencyLevel?.isVisibleIn(confirmedMode) == true) {
            put("transparencyLevels", transparencyLevel.levels.sorted().joinToString(","))
            device.confirmedNumber(DeviceField.TRANSPARENCY_LEVEL)?.let { put("transparencyLevel", it.toString()) }
        }
        val presets = device.writablePresets
        if (presets.isNotEmpty()) {
            put("eqPresets", presets.joinToString(","))
            device.confirmedText(DeviceField.EQ)?.let { put("eqPreset", it) }
        }
        if (DeviceSetting.GAME_MODE in writable) {
            put("gameModeSupported", "true")
            device.confirmedToggle(DeviceField.GAME_MODE)?.let { put("gameMode", it.toString()) }
        }
        REMOTE_CONTROL_SETTINGS.forEach { setting ->
            device.settingPrerequisite(setting)?.let { put("blocked.${setting.name}", it) }
        }
    }
}
