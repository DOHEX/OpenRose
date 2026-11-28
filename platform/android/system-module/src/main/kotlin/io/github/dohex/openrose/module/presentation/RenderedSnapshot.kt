package io.github.dohex.openrose.module.presentation

import android.bluetooth.BluetoothAdapter
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

internal data class RenderedSnapshot(
    val wire: Bundle,
    val identity: SessionIdentity,
    val readSequence: Long,
    val native: NativeHeadsetSnapshot,
    val presentation: Bundle,
    val mode: String,
    val statusNotificationEnabled: Boolean,
    val statusIsland: Boolean,
    val statusTarget: String,
    val dynamicColor: Boolean,
    val address: String?,
) {
    fun batterySummary(): String {
        val parts = buildList {
            batteryValue(DeviceField.LEFT_BATTERY)?.let { add("L $it%${chargingMark(DeviceField.LEFT_CHARGING)}") }
            batteryValue(DeviceField.RIGHT_BATTERY)?.let { add("R $it%${chargingMark(DeviceField.RIGHT_CHARGING)}") }
            batteryValue(DeviceField.CASE_BATTERY)?.let { add("C $it%") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "电量未知"
    }

    private fun chargingMark(field: DeviceField): String = if (native.knownCharging(field) == true) "⚡" else ""

    fun aodTitle(): String? = listOfNotNull(
        batteryValue(DeviceField.LEFT_BATTERY)?.let { "L $it%" },
        batteryValue(DeviceField.RIGHT_BATTERY)?.let { "R $it%" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" | ")

    fun batteryValue(field: DeviceField): Int? = native.knownBattery(field)

    fun knownNoiseMode(): String? {
        val text = ((native.fields[DeviceField.NOISE_MODE] as? FieldReading.Known)?.value as? FieldValue.Text)?.value
        val mode = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return mode.takeIf { it in availableNoiseModes() }
    }

    fun availableNoiseModes(): List<String> = NoiseMode.entries.map(NoiseMode::id).filter { it in native.noiseModes }

    fun canControlNoise(currentMode: String): Boolean = DeviceSetting.NOISE_MODE in native.writableSettings && currentMode in availableNoiseModes()

    fun nextNoiseMode(currentMode: String): String? {
        val modes = availableNoiseModes()
        if (modes.size < 2) return null
        return modes[(modes.indexOf(currentMode) + 1) % modes.size]
    }

    fun profileLabel(): String = native.profile.displayName
}

internal data class SessionIdentity(val deviceId: String, val sessionId: Long, val hostEpoch: String) {
    val stableKey: String get() = "$hostEpoch:$deviceId:$sessionId"
}

internal fun decodePresentationSnapshot(snapshot: Bundle, native: NativeHeadsetSnapshot): RenderedSnapshot? {
    val identity = sessionIdentity(snapshot) ?: return null
    if (snapshot.getInt(BridgeWire.KEY_VERSION, -1) != BridgeWire.VERSION) return null
    val presentation = snapshot.getBundle(BridgePresentationWire.PRESENTATION) ?: return null
    val mode = presentation.getString(BridgePresentationWire.MODE)?.takeIf {
        it == BridgePresentationWire.OFF || it == BridgePresentationWire.SYSTEM_STYLE || it == BridgePresentationWire.OPENROSE_STYLE
    } ?: return null
    if (!presentation.containsKey(BridgePresentationWire.STATUS_NOTIFICATION_ENABLED)) return null
    return RenderedSnapshot(
        wire = Bundle(snapshot),
        identity = identity,
        readSequence = snapshot.getLong(BridgeWire.READ_SEQUENCE, 0L),
        native = native,
        presentation = Bundle(presentation),
        mode = mode,
        statusNotificationEnabled = presentation.getBoolean(BridgePresentationWire.STATUS_NOTIFICATION_ENABLED),
        statusIsland = presentation.getBoolean(BridgePresentationWire.STATUS_SUMMARY_ISLAND, false),
        statusTarget = presentation.getString(BridgePresentationWire.STATUS_TARGET, BridgePresentationWire.TARGET_OPENROSE),
        dynamicColor = presentation.getBoolean(BridgePresentationWire.DYNAMIC_COLOR, false),
        address = native.addresses.firstOrNull(BluetoothAdapter::checkBluetoothAddress),
    )
}

internal fun sessionIdentity(snapshot: Bundle): SessionIdentity? {
    val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
    val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
    val hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
    if (sessionId == INVALID_SESSION_ID) return null
    return SessionIdentity(deviceId, sessionId, hostEpoch)
}

internal fun modeLabel(mode: String): String = when (mode) {
    "normal" -> "标准"
    "anc" -> "降噪"
    "transparency" -> "通透"
    "wind" -> "抗风噪"
    "auto" -> "自动"
    "dynamic" -> "动态降噪"
    "deep" -> "深度降噪"
    else -> mode
}

private fun NativeHeadsetSnapshot.knownValue(field: DeviceField): FieldValue? = (fields[field] as? FieldReading.Known)?.value

internal fun NativeHeadsetSnapshot.knownBattery(field: DeviceField): Int? {
    val level = (knownValue(field) as? FieldValue.Number)?.value
    return level?.takeIf { it in 0..100 }
}

internal fun NativeHeadsetSnapshot.knownCharging(field: DeviceField): Boolean? = (knownValue(field) as? FieldValue.Toggle)?.value

private const val INVALID_SESSION_ID = Long.MIN_VALUE
