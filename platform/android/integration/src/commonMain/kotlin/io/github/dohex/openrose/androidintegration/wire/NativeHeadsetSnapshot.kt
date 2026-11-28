package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.systemFields
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.dohex.openrose.ffi.kotlincore.toControlState
import uniffi.openrose_control.ControlSnapshot

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
    val ancLevel: NativeNoiseLevelCapabilities?,
    val transparencyLevel: NativeNoiseLevelCapabilities?,
    val fields: Map<DeviceField, FieldReading>,
) {
    val reference: NativeSessionReference
        get() = NativeSessionReference(hostEpoch, deviceId, sessionId)

    val supportsNoiseMode: Boolean
        get() = "NOISE_MODE" in writableSettings && noiseModes.isNotEmpty()

    fun isFresh(nowElapsedMillis: Long): Boolean = capturedElapsedMillis in 1..nowElapsedMillis &&
        nowElapsedMillis - capturedElapsedMillis <= MAX_AGE_MILLIS

    fun matchesAddress(address: String?): Boolean {
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return normalized in addresses
    }

    fun supportsNoise(mode: String): Boolean = "NOISE_MODE" in writableSettings && mode in noiseModes

    fun sameReportedState(other: NativeHeadsetSnapshot): Boolean = hostEpoch == other.hostEpoch && deviceId == other.deviceId &&
        sessionId == other.sessionId && addresses == other.addresses && profile == other.profile &&
        writableSettings == other.writableSettings && readableFields == other.readableFields && noiseModes == other.noiseModes &&
        ancLevel == other.ancLevel && transparencyLevel == other.transparencyLevel && fields == other.fields

    companion object {
        const val MAX_AGE_MILLIS = 3_000L
        const val MILINK_COMPATIBILITY_DEVICE_ID = "01010607"

        fun from(snapshot: ControlSnapshot, hostEpoch: String, capturedElapsedMillis: Long): NativeHeadsetSnapshot? {
            if (hostEpoch.isBlank() || capturedElapsedMillis <= 0L) return null
            val state = snapshot.toControlState()
            val session = state.readySession ?: return null
            val device = state.device ?: return null
            val addresses = listOfNotNull(
                normalizeBluetoothAddress(device.classicAddress),
                normalizeBluetoothAddress(device.deviceId.value),
            ).toSet().takeIf { it.isNotEmpty() } ?: return null
            val noise = device.capabilities.noiseControl
            return NativeHeadsetSnapshot(
                hostEpoch = hostEpoch,
                deviceId = session.deviceId.value,
                sessionId = session.sessionId,
                addresses = addresses,
                profile = device.profile,
                capturedElapsedMillis = capturedElapsedMillis,
                writableSettings = device.capabilities.writableSettings.mapTo(linkedSetOf()) { it.name },
                readableFields = device.capabilities.readableFields.mapTo(linkedSetOf()) { it.name },
                noiseModes = noise?.modes.orEmpty().toSet(),
                ancLevel = noise?.levelCapabilities(DeviceSetting.ANC_LEVEL)?.toNative(),
                transparencyLevel = noise?.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL)?.toNative(),
                fields = device.systemFields().mapValues { (_, reading) -> reading.toNative() },
            )
        }

        private fun NoiseLevelCapabilities.toNative() = NativeNoiseLevelCapabilities(levels.toSet(), visibleModes.toSet())

        private fun FieldReading.toNative(): FieldReading = when (this) {
            FieldReading.Loading -> FieldReading.Loading
            is FieldReading.NotProvided -> FieldReading.NotProvided("")
            is FieldReading.Unknown -> FieldReading.Unknown("")
            is FieldReading.Known -> when (value) {
                is FieldValue.Number, is FieldValue.Toggle, is FieldValue.Text -> FieldReading.Known(value, "")
                else -> FieldReading.Unknown("")
            }
        }
    }
}

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
