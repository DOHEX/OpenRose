package io.github.dohex.openrose.integration

import android.os.Bundle
import android.os.SystemClock
import io.github.dohex.openrose.bridge.BridgeWire
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
    val noiseLevels: Set<Int>,
    val fields: Map<String, NativeFieldReading>,
) {
    val reference: NativeSessionReference
        get() = NativeSessionReference(hostEpoch, deviceId, sessionId)

    val supportsNoiseMode: Boolean
        get() = "NOISE_MODE" in writableSettings && noiseModes.isNotEmpty()

    fun isSupportedProfile(): Boolean = profile == "I5_ZT" || profile == "I7_RS_V2"

    fun isFresh(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Boolean =
        isSupportedProfile() && hostEpoch.isNotBlank() && deviceId.isNotBlank() && sessionId > 0L && addresses.isNotEmpty() &&
            addresses.all { normalizeBluetoothAddress(it) == it } &&
            capturedElapsedMillis > 0L && capturedElapsedMillis <= nowElapsedMillis &&
            nowElapsedMillis - capturedElapsedMillis <= MAX_AGE_MILLIS

    fun matchesAddress(address: String?): Boolean {
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return normalized in addresses
    }

    fun supportsNoise(mode: String): Boolean =
        supportsNoiseMode && mode in noiseModes && "NOISE_MODE" in writableSettings

    fun knownText(field: String): String? =
        (fields[field] as? NativeFieldReading.Known)?.value?.let { it as? NativeFieldValue.Text }?.value

    fun knownNumber(field: String): Int? =
        (fields[field] as? NativeFieldReading.Known)?.value?.let { it as? NativeFieldValue.Number }?.value

    fun knownToggle(field: String): Boolean? =
        (fields[field] as? NativeFieldReading.Known)?.value?.let { it as? NativeFieldValue.Toggle }?.value

    fun sameReportedState(other: NativeHeadsetSnapshot): Boolean =
        hostEpoch == other.hostEpoch && deviceId == other.deviceId && sessionId == other.sessionId &&
            addresses == other.addresses && profile == other.profile && writableSettings == other.writableSettings &&
            readableFields == other.readableFields && noiseModes == other.noiseModes && noiseLevels == other.noiseLevels &&
            fields == other.fields

    /**
     * Parse only the primitive bridge wire fields needed by the native adapters. Every nested
     * Bundle is consumed immediately and copied into immutable Kotlin values so callers cannot
     * mutate an adapter's published state afterward.
     */
    companion object {
        const val MAX_AGE_MILLIS = 3_000L
        const val MILINK_COMPATIBILITY_DEVICE_ID = "01010607"

        fun fromBundle(bundle: Bundle?): NativeHeadsetSnapshot? =
            runCatching { parseBundle(bundle) }.getOrNull()

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

            val fields = linkedMapOf<String, NativeFieldReading>()
            fieldsBundle.keySet().forEach { field ->
                if (field.length > 64) return@forEach
                fieldsBundle.getBundle(field)?.let { reading ->
                    parseReading(reading)?.let { fields[field] = it }
                }
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
                noiseLevels = integerValues(noise, "levels").filterTo(linkedSetOf()) { it in 0..100 }.toSet(),
                fields = fields.toMap(),
            )
        }

        private fun stringValues(bundle: Bundle?, key: String): List<String> =
            (runCatching { bundle?.get(key) }.getOrNull() as? ArrayList<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()

        private fun integerValues(bundle: Bundle?, key: String): List<Int> =
            (runCatching { bundle?.get(key) }.getOrNull() as? ArrayList<*>)
                ?.mapNotNull { it as? Int }
                .orEmpty()

        private fun parseReading(bundle: Bundle): NativeFieldReading? {
            val status = bundle.getString("status") ?: return null
            return when (status) {
                "LOADING" -> NativeFieldReading.Loading
                "UNKNOWN" -> NativeFieldReading.Unknown
                "NOT_PROVIDED" -> NativeFieldReading.NotProvided
                "KNOWN" -> {
                    val value = bundle.getBundle("value") ?: return NativeFieldReading.Unknown
                    val type = value.getString("type") ?: return NativeFieldReading.Unknown
                    val primitive = runCatching { value.get("value") }.getOrNull()
                    when (type) {
                        "NUMBER" -> (primitive as? Int)?.let { NativeFieldReading.Known(NativeFieldValue.Number(it)) }
                            ?: NativeFieldReading.Unknown
                        "TOGGLE" -> (primitive as? Boolean)?.let { NativeFieldReading.Known(NativeFieldValue.Toggle(it)) }
                            ?: NativeFieldReading.Unknown
                        "TEXT" -> (primitive as? String)?.takeIf { it.length <= 128 }
                            ?.let { NativeFieldReading.Known(NativeFieldValue.Text(it)) }
                            ?: NativeFieldReading.Unknown
                        else -> NativeFieldReading.Unknown
                    }
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

        private fun normalizeNoiseMode(mode: String): String? =
            mode.trim().lowercase(Locale.ROOT).takeIf { it.length in 1..32 }
    }
}

data class NativeSessionReference(
    val hostEpoch: String,
    val deviceId: String,
    val sessionId: Long,
)

/** Retains exact managed addresses until unhook, so elapsed time never reopens an OEM action path. */
internal class NativeManagedAddressTombstones {
    // These addresses come only from authenticated, ready ControlSession snapshots. Keeping the
    // small history for this module generation avoids a TTL or eviction admitting a stale action.
    private val addresses = linkedSetOf<String>()

    @Synchronized
    fun remember(snapshot: NativeHeadsetSnapshot) {
        snapshot.addresses.mapNotNullTo(addresses, NativeHeadsetSnapshot::normalizeBluetoothAddress)
    }

    @Synchronized
    fun contains(address: String?): Boolean = NativeHeadsetSnapshot.normalizeBluetoothAddress(address) in addresses

    @Synchronized
    fun clear() { addresses.clear() }
}

sealed interface NativeFieldReading {
    data object Loading : NativeFieldReading
    data object Unknown : NativeFieldReading
    data object NotProvided : NativeFieldReading
    data class Known(val value: NativeFieldValue) : NativeFieldReading
}

sealed interface NativeFieldValue {
    data class Number(val value: Int) : NativeFieldValue
    data class Toggle(val value: Boolean) : NativeFieldValue
    data class Text(val value: String) : NativeFieldValue
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
}

/** Coalesces two synchronous vendor entry points for one UI tap without owning a command queue. */
internal object NativeNoiseRequestGate {
    private const val DEDUPE_WINDOW_MILLIS = 250L
    private val lock = Any()
    private val sequence = AtomicLong()
    private val reservations = mutableMapOf<RequestKey, Reservation>()

    fun submit(snapshot: NativeHeadsetSnapshot, mode: String, enqueue: () -> Boolean): Result {
        val now = SystemClock.elapsedRealtime()
        val key = RequestKey(snapshot.hostEpoch, snapshot.deviceId, snapshot.sessionId, mode)
        val token = synchronized(lock) {
            reservations.entries.removeAll { it.value.expiresAt <= now }
            if (key in reservations) return Result.DUPLICATE
            val reservation = sequence.incrementAndGet()
            reservations[key] = Reservation(reservation, Long.MAX_VALUE)
            reservation
        }

        val accepted = runCatching(enqueue).getOrDefault(false)
        synchronized(lock) {
            val reservation = reservations[key]
            if (reservation?.token == token) {
                if (accepted) reservation.expiresAt = SystemClock.elapsedRealtime() + DEDUPE_WINDOW_MILLIS
                else reservations.remove(key)
            }
        }
        return if (accepted) Result.ACCEPTED else Result.REJECTED
    }

    enum class Result { ACCEPTED, DUPLICATE, REJECTED }

    private data class RequestKey(
        val hostEpoch: String,
        val deviceId: String,
        val sessionId: Long,
        val mode: String,
    )

    private data class Reservation(val token: Long, var expiresAt: Long)
}
