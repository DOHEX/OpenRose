package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

/** A value copied from the authenticated bridge's latest device snapshot. */
data class NativeHeadsetSnapshot(
    val hostEpoch: String,
    val deviceId: String,
    val sessionId: Long,
    val addresses: Set<String>,
    val profile: ModelProfile,
    val capturedElapsedMillis: Long,
    val writableSettings: Set<String>,
    val readableFields: Set<String>,
    val noiseModes: Set<String>,
    /** Null means the model does not expose this setting as a capability. */
    val ancLevel: NativeNoiseLevelCapabilities?,
    /** Null means the model does not expose this setting as a capability. */
    val transparencyLevel: NativeNoiseLevelCapabilities?,
    val fields: Map<DeviceField, FieldReading>,
) {
    val reference: NativeSessionReference
        get() = NativeSessionReference(hostEpoch, deviceId, sessionId)

    val supportsNoiseMode: Boolean
        get() = "NOISE_MODE" in writableSettings && noiseModes.isNotEmpty()

    /** [nowElapsedMillis] is the reader's elapsed-realtime clock, the same clock the host stamped [capturedElapsedMillis] with. */
    fun isFresh(nowElapsedMillis: Long): Boolean = hostEpoch.isNotBlank() &&
        deviceId.isNotBlank() &&
        sessionId > 0L &&
        addresses.isNotEmpty() && addresses.all { normalizeBluetoothAddress(it) == it } &&
        capturedElapsedMillis > 0L && capturedElapsedMillis <= nowElapsedMillis &&
        nowElapsedMillis - capturedElapsedMillis <= MAX_AGE_MILLIS

    fun matchesAddress(address: String?): Boolean {
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return normalized in addresses
    }

    fun supportsNoise(mode: String): Boolean = supportsNoiseMode && mode in noiseModes && "NOISE_MODE" in writableSettings

    fun sameReportedState(other: NativeHeadsetSnapshot): Boolean = hostEpoch == other.hostEpoch && deviceId == other.deviceId &&
        sessionId == other.sessionId && addresses == other.addresses && profile == other.profile &&
        writableSettings == other.writableSettings && readableFields == other.readableFields && noiseModes == other.noiseModes &&
        ancLevel == other.ancLevel && transparencyLevel == other.transparencyLevel && fields == other.fields

    /**
     * Parse only the primitive bridge wire fields needed by the native adapters. Every nested
     * tree is consumed immediately and copied into immutable Kotlin values so callers cannot
     * mutate an adapter's published state afterward.
     */
    companion object {
        const val MAX_AGE_MILLIS = 3_000L
        const val MILINK_COMPATIBILITY_DEVICE_ID = "01010607"

        /** Null for an absent, foreign-version, incomplete or unreadable snapshot. */
        fun fromWire(wire: WireMap?): NativeHeadsetSnapshot? = runCatching { parseWire(wire) }.getOrNull()

        private fun parseWire(wire: WireMap?): NativeHeadsetSnapshot? {
            wire ?: return null

            if (wire.int(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) return null

            val hostEpoch = wire.string(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            val deviceId = wire.string(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val sessionId = wire.long(BridgeWire.SESSION_ID)?.takeIf { it > 0L } ?: return null
            val profile = ModelProfile.fromName(wire.string("profile")) ?: return null
            val addresses = parseAddresses(wire, deviceId).takeIf { it.isNotEmpty() } ?: return null
            val capturedElapsedMillis = wire.long(BridgeWire.CAPTURED_ELAPSED_MILLIS)?.takeIf { it > 0L } ?: return null
            val capabilities = wire.child("capabilities") ?: return null
            val fieldsWire = wire.child("fields") ?: return null

            val noise = capabilities.child("noise")
            return NativeHeadsetSnapshot(
                hostEpoch = hostEpoch,
                deviceId = deviceId,
                sessionId = sessionId,
                addresses = addresses,
                profile = profile,
                capturedElapsedMillis = capturedElapsedMillis,
                writableSettings = stringValues(capabilities, "writable_settings")
                    .filterTo(linkedSetOf()) { it.length <= 64 }.toSet(),
                readableFields = stringValues(capabilities, "readable_fields")
                    .filterTo(linkedSetOf()) { it.length <= 64 }.toSet(),
                noiseModes = stringValues(noise, "modes").mapNotNull(::normalizeNoiseMode).toSet(),
                ancLevel = parseNoiseLevelCapabilities(noise, "anc_level"),
                transparencyLevel = parseNoiseLevelCapabilities(noise, "transparency_level"),
                fields = parseFields(fieldsWire),
            )
        }

        private fun parseAddresses(wire: WireMap, deviceId: String): Set<String> = buildSet {
            normalizeBluetoothAddress(wire.string("device_address"))?.let(::add)
            normalizeBluetoothAddress(wire.string("classic_address"))?.let(::add)
            stringValues(wire, "addresses").mapNotNullTo(this, ::normalizeBluetoothAddress)
            // Android DeviceId is commonly the BLE address. An opaque ID is never an address.
            normalizeBluetoothAddress(deviceId)?.let(::add)
        }

        private fun parseFields(wire: WireMap): Map<DeviceField, FieldReading> = buildMap {
            for (name in wire.keys) {
                val field = DeviceField.entries.firstOrNull { it.name == name } ?: continue
                wire.child(name)?.let(::parseReading)?.let { put(field, it) }
            }
        }

        private fun parseNoiseLevelCapabilities(noise: WireMap?, key: String): NativeNoiseLevelCapabilities? {
            val capability = noise?.child(key) ?: return null
            return NativeNoiseLevelCapabilities(
                levels = integerValues(capability, "levels").filterTo(linkedSetOf()) { it in 0..100 }.toSet(),
                visibleModes = stringValues(capability, "visible_modes").mapNotNull(::normalizeNoiseMode).toSet(),
            )
        }

        private fun stringValues(wire: WireMap?, key: String): List<String> = wire?.strings(key).orEmpty()

        private fun integerValues(wire: WireMap?, key: String): List<Int> = wire?.ints(key).orEmpty()

        // The wire carries values only; the raw device bytes never reach a system entry.
        private fun parseReading(wire: WireMap): FieldReading? = when (wire.string(BridgeWire.STATUS)) {
            "LOADING" -> FieldReading.Loading
            "UNKNOWN" -> FieldReading.Unknown("")
            "NOT_PROVIDED" -> FieldReading.NotProvided("")
            "KNOWN" -> wire.child("value")?.let(::parseValue)?.let { FieldReading.Known(it, "") } ?: FieldReading.Unknown("")
            else -> null
        }

        private fun parseValue(wire: WireMap): FieldValue? = when (wire.string("type")) {
            "NUMBER" -> wire.int("value")?.let(FieldValue::Number)
            "TOGGLE" -> wire.boolean("value")?.let(FieldValue::Toggle)
            "TEXT" -> wire.string("value")?.takeIf { it.length <= 128 }?.let(FieldValue::Text)
            else -> null
        }

        private fun normalizeNoiseMode(mode: String): String? = mode.trim().lowercase().takeIf { it.length in 1..32 }
    }
}

/** A copied, setting-specific noise-level capability from the authenticated bridge snapshot. */
data class NativeNoiseLevelCapabilities(
    val levels: Set<Int>,
    val visibleModes: Set<String>,
) {
    fun isVisibleIn(mode: String?): Boolean = levels.isNotEmpty() && mode?.trim()?.lowercase()?.let(visibleModes::contains) == true
}

data class NativeSessionReference(
    val hostEpoch: String,
    val deviceId: String,
    val sessionId: Long,
)
