package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.AudioProfileEvidence
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportFacts
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

internal suspend fun ConnectionAttempt.selectTransport(transport: ControlTransport, timeoutMillis: Long): TransportSelection {
    val profile = requireNotNull(candidate.profile)
    val gatt = profile.gatt
    val ble = TransportEndpoint.BleGatt(gatt.serviceUuid, gatt.writeUuid, gatt.notifyUuid)
    val sppUuid = profile.spec.sppControlUuid ?: return TransportSelection(ble, TransportSelectionReason.MODEL_BLE_ONLY)
    if (forceBle) return TransportSelection(ble, TransportSelectionReason.FORCED_BLE)
    val address = candidate.classicAddress
        ?: return TransportSelection(ble, TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE)

    val facts = try {
        withTimeout(timeoutMillis) {
            transport.connectionFacts(candidate.deviceId, address)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TransportFacts()
    }
    if (!facts.sppAvailable) return TransportSelection(ble, TransportSelectionReason.SPP_UNAVAILABLE)
    return when (facts.audioProfile) {
        AudioProfileEvidence.UNKNOWN ->
            TransportSelection(ble, TransportSelectionReason.AUDIO_PROFILE_UNKNOWN)

        AudioProfileEvidence.LE_AUDIO ->
            TransportSelection(ble, TransportSelectionReason.LE_AUDIO)

        AudioProfileEvidence.CLASSIC ->
            TransportSelection(TransportEndpoint.SppRfcomm(address, sppUuid), TransportSelectionReason.CLASSIC_AUDIO)
    }
}
