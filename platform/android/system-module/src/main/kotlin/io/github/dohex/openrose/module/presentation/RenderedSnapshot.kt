package io.github.dohex.openrose.module.presentation

import android.bluetooth.BluetoothAdapter
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.ModelProfile

internal data class RenderedSnapshot(
    val wire: Bundle,
    val identity: SessionIdentity,
    val readSequence: Long,
    val fields: Bundle,
    val capabilities: Bundle,
    val presentation: Bundle,
    val mode: String,
    val focusEnabled: Boolean,
    val statusIsland: Boolean,
    val statusTarget: String,
    val dynamicColor: Boolean,
    val address: String?,
) {
    fun batterySummary(): String {
        val parts = buildList {
            batteryValue(LEFT_BATTERY)?.let { add("L $it%${chargingMark(LEFT_CHARGING)}") }
            batteryValue(RIGHT_BATTERY)?.let { add("R $it%${chargingMark(RIGHT_CHARGING)}") }
            batteryValue(CASE_BATTERY)?.let { add("C $it%") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "电量未知"
    }

    /** Only a confirmed charging reading is marked; unknown charging leaves the battery text neutral. */
    private fun chargingMark(fieldName: String): String {
        val reading = fields.getBundle(fieldName) ?: return ""
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return ""
        val value = reading.getBundle("value") ?: return ""
        return if (value.getString("type") == TOGGLE && value.getBoolean("value", false)) "⚡" else ""
    }

    fun aodTitle(): String? = listOfNotNull(
        batteryValue(LEFT_BATTERY)?.let { "L $it%" },
        batteryValue(RIGHT_BATTERY)?.let { "R $it%" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" | ")

    fun batteryValue(fieldName: String): Int? {
        val reading = fields.getBundle(fieldName) ?: return null
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return null
        val value = reading.getBundle("value") ?: return null
        if (value.getString("type") != NUMBER) return null
        return value.getInt("value", INVALID_BATTERY).takeIf { it in 0..100 }
    }

    fun knownNoiseMode(): String? {
        val reading = fields.getBundle(NOISE_FIELD) ?: return null
        if (reading.getString(BridgeWire.STATUS) != KNOWN) return null
        val value = reading.getBundle("value") ?: return null
        if (value.getString("type") != TEXT) return null
        val mode = value.getString("value")?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val modes = availableNoiseModes()
        return mode.takeIf { it in modes }
    }

    fun availableNoiseModes(): List<String> {
        val modeBundle = capabilities.getBundle("noise") ?: return emptyList()
        val modes = modeBundle.getStringArrayList("modes")?.map { it.lowercase() }?.toSet() ?: return emptyList()
        return NoiseMode.entries.map(NoiseMode::id).filter { it in modes }
    }

    fun canControlNoise(currentMode: String): Boolean {
        val writable = capabilities.getStringArrayList("writable_settings") ?: return false
        return "NOISE_MODE" in writable && currentMode in availableNoiseModes()
    }

    /** The mode after the confirmed one in this model's order; null when there is nothing to switch to. */
    fun nextNoiseMode(currentMode: String): String? {
        val modes = availableNoiseModes()
        if (modes.size < 2) return null
        return modes[(modes.indexOf(currentMode) + 1) % modes.size]
    }

    fun profileLabel(): String = ModelProfile.fromName(wire.getString("profile"))?.displayName ?: "OpenRose 耳机"
}

internal data class SessionIdentity(val deviceId: String, val sessionId: Long, val hostEpoch: String) {
    val stableKey: String get() = "$hostEpoch:$deviceId:$sessionId"
}

internal fun decodePresentationSnapshot(snapshot: Bundle): RenderedSnapshot? {
    val identity = sessionIdentity(snapshot) ?: return null
    if (snapshot.getInt(BridgeWire.KEY_VERSION, -1) != BridgeWire.VERSION) return null
    val fields = snapshot.getBundle("fields") ?: return null
    val capabilities = snapshot.getBundle("capabilities") ?: return null
    val presentation = snapshot.getBundle(BridgePresentationWire.PRESENTATION) ?: return null
    val mode = presentation.getString(BridgePresentationWire.MODE)?.takeIf {
        it == BridgePresentationWire.OFF || it == BridgePresentationWire.OFFICIAL || it == BridgePresentationWire.MODULE
    } ?: return null
    if (!presentation.containsKey(BridgePresentationWire.FOCUS_ENABLED)) return null
    return RenderedSnapshot(
        wire = Bundle(snapshot),
        identity = identity,
        readSequence = snapshot.getLong(BridgeWire.READ_SEQUENCE, 0L),
        fields = Bundle(fields),
        capabilities = Bundle(capabilities),
        presentation = Bundle(presentation),
        mode = mode,
        focusEnabled = presentation.getBoolean(BridgePresentationWire.FOCUS_ENABLED),
        statusIsland = presentation.getBoolean(BridgePresentationWire.STATUS_SUMMARY_ISLAND, false),
        statusTarget = presentation.getString(BridgePresentationWire.STATUS_TARGET, BridgePresentationWire.TARGET_OPENROSE),
        dynamicColor = presentation.getBoolean(BridgePresentationWire.DYNAMIC_COLOR, false),
        address = bluetoothAddress(snapshot),
    )
}

/** The classic address names the system's audio device; an address-shaped control ID is the fallback. */
private fun bluetoothAddress(snapshot: Bundle): String? {
    val address = snapshot.getString(CLASSIC_ADDRESS) ?: snapshot.getString(DEVICE_ADDRESS)
    return address?.takeIf(BluetoothAdapter::checkBluetoothAddress)
}

internal fun sessionIdentity(snapshot: Bundle): SessionIdentity? {
    val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
    val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
    val hostEpoch = snapshot.getString(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
    if (sessionId == INVALID_SESSION_ID) return null
    return SessionIdentity(deviceId, sessionId, hostEpoch)
}

/** A copy of the app's `noiseModeLabel` (sharedUI `NoiseControlCard.kt`), so this process need not load the UI module. */
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

private const val KNOWN = "KNOWN"
private const val NUMBER = "NUMBER"
private const val TEXT = "TEXT"
private const val TOGGLE = "TOGGLE"
private const val NOISE_FIELD = "NOISE_MODE"
private const val LEFT_CHARGING = "LEFT_CHARGING"
private const val RIGHT_CHARGING = "RIGHT_CHARGING"
private const val CLASSIC_ADDRESS = "classic_address"
private const val DEVICE_ADDRESS = "device_address"
private const val LEFT_BATTERY = "LEFT_BATTERY"
private const val RIGHT_BATTERY = "RIGHT_BATTERY"
private const val CASE_BATTERY = "CASE_BATTERY"
private const val INVALID_SESSION_ID = Long.MIN_VALUE
private const val INVALID_BATTERY = -1
