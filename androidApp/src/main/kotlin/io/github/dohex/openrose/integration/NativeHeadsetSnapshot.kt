package io.github.dohex.openrose.integration

import android.os.Bundle
import android.os.SystemClock
import io.github.dohex.openrose.androidintegration.SystemRequestGate
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** A value copied from the authenticated bridge's latest device snapshot. */
data class NativeHeadsetSnapshot(
    val hostEpoch: String,
    val deviceId: String,
    val sessionId: Long,
    val addresses: Set<String>,
    val profile: String,
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

    fun isSupportedProfile(): Boolean = profile == "I5_ZT" || profile == "I7_RS_V2"

    fun isFresh(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Boolean = isSupportedProfile() && hostEpoch.isNotBlank() && deviceId.isNotBlank() && sessionId > 0L && addresses.isNotEmpty() &&
        addresses.all { normalizeBluetoothAddress(it) == it } &&
        capturedElapsedMillis > 0L && capturedElapsedMillis <= nowElapsedMillis &&
        nowElapsedMillis - capturedElapsedMillis <= MAX_AGE_MILLIS

    fun matchesAddress(address: String?): Boolean {
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return normalized in addresses
    }

    fun supportsNoise(mode: String): Boolean = supportsNoiseMode && mode in noiseModes && "NOISE_MODE" in writableSettings

    fun sameReportedState(other: NativeHeadsetSnapshot): Boolean = hostEpoch == other.hostEpoch && deviceId == other.deviceId && sessionId == other.sessionId &&
        addresses == other.addresses && profile == other.profile && writableSettings == other.writableSettings &&
        readableFields == other.readableFields && noiseModes == other.noiseModes && ancLevel == other.ancLevel &&
        transparencyLevel == other.transparencyLevel &&
        fields == other.fields

    /**
     * Parse only the primitive bridge wire fields needed by the native adapters. Every nested
     * Bundle is consumed immediately and copied into immutable Kotlin values so callers cannot
     * mutate an adapter's published state afterward.
     */
    companion object {
        const val MAX_AGE_MILLIS = 3_000L
        const val MILINK_COMPATIBILITY_DEVICE_ID = "01010607"

        fun fromBundle(bundle: Bundle?): NativeHeadsetSnapshot? = runCatching { parseBundle(bundle) }.getOrNull()

        private fun parseBundle(bundle: Bundle?): NativeHeadsetSnapshot? {
            bundle ?: return null

            if (bundle.getInt("version", -1) != BridgeWire.VERSION) return null

            val hostEpoch = bundle.getString("host_epoch")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            val deviceId = bundle.getString("device_id")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val sessionId = bundle.getLong("session_id", -1L).takeIf { it > 0L } ?: return null
            val profile = bundle.getString("profile")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            if (profile != "I5_ZT" && profile != "I7_RS_V2") return null

            val addresses = linkedSetOf<String>()
            normalizeBluetoothAddress(bundle.getString("device_address"))?.let(addresses::add)
            normalizeBluetoothAddress(bundle.getString("classic_address"))?.let(addresses::add)
            stringValues(bundle, "addresses").forEach { raw ->
                normalizeBluetoothAddress(raw)?.let(addresses::add)
            }
            // Android DeviceId is commonly the BLE address. Include it only when it is an
            // actual Bluetooth address; an opaque DeviceId must never be treated as an address.
            normalizeBluetoothAddress(deviceId)?.let(addresses::add)
            if (addresses.isEmpty()) return null

            val capturedElapsedMillis = bundle.getLong("captured_elapsed_millis", -1L)
                .takeIf { it > 0L } ?: return null
            val capabilities = bundle.getBundle("capabilities") ?: return null
            val noise = capabilities.getBundle("noise")
            val fieldsBundle = bundle.getBundle("fields") ?: return null

            val fields = linkedMapOf<DeviceField, FieldReading>()
            fieldsBundle.keySet().forEach { name ->
                val field = DeviceField.entries.firstOrNull { it.name == name } ?: return@forEach
                fieldsBundle.getBundle(name)?.let { reading -> parseReading(reading)?.let { fields[field] = it } }
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

        private fun parseNoiseLevelCapabilities(noise: Bundle?, key: String): NativeNoiseLevelCapabilities? {
            val capability = runCatching { noise?.getBundle(key) }.getOrNull() ?: return null
            return NativeNoiseLevelCapabilities(
                levels = integerValues(capability, "levels").filterTo(linkedSetOf()) { it in 0..100 }.toSet(),
                visibleModes = stringValues(capability, "visible_modes").mapNotNull(::normalizeNoiseMode).toSet(),
            )
        }

        private fun stringValues(bundle: Bundle?, key: String): List<String> = (runCatching { bundle?.get(key) }.getOrNull() as? ArrayList<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()

        private fun integerValues(bundle: Bundle?, key: String): List<Int> = (runCatching { bundle?.get(key) }.getOrNull() as? ArrayList<*>)
            ?.mapNotNull { it as? Int }
            .orEmpty()

        private fun parseReading(bundle: Bundle): FieldReading? {
            // The wire carries values only; the raw device bytes never reach a system entry.
            return when (bundle.getString("status") ?: return null) {
                "LOADING" -> FieldReading.Loading

                "UNKNOWN" -> FieldReading.Unknown("")

                "NOT_PROVIDED" -> FieldReading.NotProvided("")

                "KNOWN" -> {
                    val value = bundle.getBundle("value") ?: return FieldReading.Unknown("")
                    val type = value.getString("type") ?: return FieldReading.Unknown("")
                    val primitive = runCatching { value.get("value") }.getOrNull()
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

        fun normalizeBluetoothAddress(address: String?): String? {
            val value = address?.trim()?.uppercase(Locale.ROOT) ?: return null
            if (value.length != 17) return null
            for (index in value.indices) {
                if (index % 3 == 2) {
                    if (value[index] != ':') return null
                } else if (value[index] !in '0'..'9' && value[index] !in 'A'..'F') {
                    return null
                }
            }
            return value
        }

        private fun normalizeNoiseMode(mode: String): String? = mode.trim().lowercase(Locale.ROOT).takeIf { it.length in 1..32 }
    }
}

/** A copied, setting-specific noise-level capability from the authenticated bridge snapshot. */
data class NativeNoiseLevelCapabilities(
    val levels: Set<Int>,
    val visibleModes: Set<String>,
) {
    fun isVisibleIn(mode: String?): Boolean = levels.isNotEmpty() && mode?.trim()?.lowercase(Locale.ROOT)?.let(visibleModes::contains) == true
}

data class NativeSessionReference(
    val hostEpoch: String,
    val deviceId: String,
    val sessionId: Long,
)

/** Retains exact managed addresses until unhook, so elapsed time never reopens an OEM action path. */
internal class NativeManagedAddressTombstones {
    // These addresses come only from authenticated, ready control session snapshots. Keeping the
    // small history for this module generation avoids a TTL or eviction admitting a stale action.
    private val addresses = linkedSetOf<String>()

    @Synchronized
    fun remember(snapshot: NativeHeadsetSnapshot) {
        snapshot.addresses.mapNotNullTo(addresses, NativeHeadsetSnapshot::normalizeBluetoothAddress)
    }

    @Synchronized
    fun contains(address: String?): Boolean = NativeHeadsetSnapshot.normalizeBluetoothAddress(address) in addresses

    @Synchronized
    fun clear() {
        addresses.clear()
    }
}

/** Callbacks are supplied by the authenticated reader lease in the target process. */
interface AuthenticatedNativeHeadsetCallbacks {
    /**
     * Enqueue a request against this exact bridge snapshot and return immediately. This must not
     * perform Binder IPC or wait for device confirmation on the native method's caller thread.
     */
    fun submitNoise(snapshot: NativeHeadsetSnapshot, mode: String): Boolean

    /**
     * Report adapter diagnostics through the existing authenticated bridge; detail must be
     * bounded, and this callback must enqueue locally without waiting for Binder IPC.
     */
    fun report(feature: String, status: String, detail: String)

    /**
     * The control device the fusion center's settings entry for [address] should open in OpenRose, decided by
     * the module host from its own state, or null when the system's own entry must run. Waits a bounded time.
     */
    fun fusionEntryDevice(address: String): String?
}

/** Coalesces two synchronous vendor entry points for one UI tap without owning a command queue. */
internal object NativeNoiseRequestGate {
    private val gate = SystemRequestGate({ SystemClock.elapsedRealtime() })

    fun submit(snapshot: NativeHeadsetSnapshot, mode: String, enqueue: () -> Boolean): Result = when (gate.submit(Key(snapshot.hostEpoch, snapshot.deviceId, snapshot.sessionId, mode), enqueue)) {
        SystemRequestGate.Result.ACCEPTED -> Result.ACCEPTED
        SystemRequestGate.Result.DUPLICATE -> Result.DUPLICATE
        SystemRequestGate.Result.REJECTED -> Result.REJECTED
    }

    enum class Result { ACCEPTED, DUPLICATE, REJECTED }

    private data class Key(val hostEpoch: String, val deviceId: String, val sessionId: Long, val mode: String)
}
