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

            if ((wire.int(BridgeWire.KEY_VERSION) ?: -1) != BridgeWire.VERSION) return null

            val hostEpoch = wire.string(BridgeWire.HOST_EPOCH)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            val deviceId = wire.string(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val sessionId = wire.long(BridgeWire.SESSION_ID)?.takeIf { it > 0L } ?: return null
            val profile = ModelProfile.fromName(wire.string("profile")) ?: return null

            val addresses = linkedSetOf<String>()
            normalizeBluetoothAddress(wire.string("device_address"))?.let(addresses::add)
            normalizeBluetoothAddress(wire.string("classic_address"))?.let(addresses::add)
            stringValues(wire, "addresses").forEach { raw ->
                normalizeBluetoothAddress(raw)?.let(addresses::add)
            }
            // Android DeviceId is commonly the BLE address. Include it only when it is an
            // actual Bluetooth address; an opaque DeviceId must never be treated as an address.
            normalizeBluetoothAddress(deviceId)?.let(addresses::add)
            if (addresses.isEmpty()) return null

            val capturedElapsedMillis = wire.long("captured_elapsed_millis")?.takeIf { it > 0L } ?: return null
            val capabilities = wire.child("capabilities") ?: return null
            val noise = capabilities.child("noise")
            val fieldsWire = wire.child("fields") ?: return null

            val fields = linkedMapOf<DeviceField, FieldReading>()
            fieldsWire.keys.forEach { name ->
                val field = DeviceField.entries.firstOrNull { it.name == name } ?: return@forEach
                fieldsWire.child(name)?.let { reading -> parseReading(reading)?.let { fields[field] = it } }
            }

            return NativeHeadsetSnapshot(
                hostEpoch = hostEpoch,
                deviceId = deviceId,
                sessionId = sessionId,
                addresses = addresses.toSet(),
                profile = profile,
                capturedElapsedMillis = capturedElapsedMillis,
                writableSettings = stringValues(capabilities, "writable_settings")
                    .filterTo(linkedSetOf()) { it.length <= 64 }.toSet(),
                readableFields = stringValues(capabilities, "readable_fields")
                    .filterTo(linkedSetOf()) { it.length <= 64 }.toSet(),
                noiseModes = stringValues(noise, "modes").mapNotNull(::normalizeNoiseMode).toSet(),
                ancLevel = parseNoiseLevelCapabilities(noise, "anc_level"),
                transparencyLevel = parseNoiseLevelCapabilities(noise, "transparency_level"),
                fields = fields.toMap(),
            )
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

        private fun parseReading(wire: WireMap): FieldReading? {
            // The wire carries values only; the raw device bytes never reach a system entry.
            return when (wire.string(BridgeWire.STATUS) ?: return null) {
                "LOADING" -> FieldReading.Loading

                "UNKNOWN" -> FieldReading.Unknown("")

                "NOT_PROVIDED" -> FieldReading.NotProvided("")

                "KNOWN" -> {
                    val value = wire.child("value") ?: return FieldReading.Unknown("")
                    val type = value.string("type") ?: return FieldReading.Unknown("")
                    val primitive = value.value("value")
                    when (type) {
                        "NUMBER" -> (primitive as? Int)?.let { FieldReading.Known(FieldValue.Number(it), "") }
                        "TOGGLE" -> (primitive as? Boolean)?.let { FieldReading.Known(FieldValue.Toggle(it), "") }
                        "TEXT" -> (primitive as? String)?.takeIf { it.length <= 128 }?.let { FieldReading.Known(FieldValue.Text(it), "") }
                        else -> null
                    } ?: FieldReading.Unknown("")
                }

                else -> null
            }
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

/** Retains exact managed addresses until unhook, so elapsed time never reopens an OEM action path. */
class NativeManagedAddressTombstones {
    // These addresses come only from authenticated, ready control session snapshots. Keeping the
    // small history for this module generation avoids a TTL or eviction admitting a stale action.
    private val lock = Any()
    private val addresses = linkedSetOf<String>()

    fun remember(snapshot: NativeHeadsetSnapshot) {
        synchronized(lock) { snapshot.addresses.mapNotNullTo(addresses, ::normalizeBluetoothAddress) }
    }

    fun contains(address: String?): Boolean = synchronized(lock) { normalizeBluetoothAddress(address) in addresses }

    fun clear() {
        synchronized(lock) { addresses.clear() }
    }
}
