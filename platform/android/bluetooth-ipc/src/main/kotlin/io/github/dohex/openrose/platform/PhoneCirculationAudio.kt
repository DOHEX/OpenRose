package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.LocalAudio
import uniffi.openrose_ffi.YieldRefusalKind

private val unreadableState = YieldRefusalKind.AUDIO_STATE_UNREADABLE
private val platformUnavailable = YieldRefusalKind.PLATFORM_UNAVAILABLE

@SuppressLint("MissingPermission")
class PhoneCirculationAudio(private val context: Context) : LocalAudio {
    private val profiles = AndroidAudioProfiles(context)

    override suspend fun isConnected(address: String): Boolean = withProfiles(address, unreadableState) { device, leases ->
        leases.any { it.currentProxy().getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }
    }

    override suspend fun isMediaConnected(address: String): Boolean = withProfiles(address, platformUnavailable) { device, leases ->
        leases.first().currentProxy().getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
    }

    override suspend fun yieldAudio(address: String) {
        checkAudioAvailability()
        change(address, connect = false)
    }

    override suspend fun acquire(address: String) = change(address, connect = true)

    private fun checkAudioAvailability() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val mode = audioManager?.mode
        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            context.getSystemService(TelecomManager::class.java)?.isInCall == true
        if (inCall) throw refused(YieldRefusalKind.IN_CALL, "phone in call")
        if (audioManager?.activeRecordingConfigurations?.isNotEmpty() == true) {
            throw refused(YieldRefusalKind.MIC_IN_USE, "active recording")
        }
    }

    private suspend fun change(address: String, connect: Boolean) = withProfiles(address, platformUnavailable) { device, leases ->
        val initialStates = leases.map { it.currentProxy().getConnectionState(device) }
        if (initialStates.any { it == BluetoothProfile.STATE_CONNECTING || it == BluetoothProfile.STATE_DISCONNECTING }) {
            throw refused(platformUnavailable, "audio connection is changing")
        }
        issueChange(device, leases, connect)
        val observed = withTimeoutOrNull(12_000L) {
            while (true) {
                val states = leases.map { it.currentProxy().getConnectionState(device) }
                val reached = if (connect) {
                    states.first() == BluetoothProfile.STATE_CONNECTED
                } else {
                    states.all { it == BluetoothProfile.STATE_DISCONNECTED }
                }
                if (reached) break
                delay(120)
            }
            true
        }
        if (observed != true) {
            val timedOut = if (connect) "audio connect timed out" else "audio disconnect timed out"
            throw refused(platformUnavailable, timedOut)
        }
    }

    private fun issueChange(
        device: BluetoothDevice,
        leases: List<AndroidAudioProfiles.ProfileProxyLease>,
        connect: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= 37) {
            val status = if (connect) device.connect() else device.disconnect()
            if (status != BluetoothStatusCodes.SUCCESS) {
                throw refused(platformUnavailable, "bluetooth rejected the audio operation ($status)")
            }
            return
        }
        val operation = if (connect) "connect" else "disconnect"
        val target = if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
        leases.map { it.currentProxy() }.forEach { profile ->
            if (profile.getConnectionState(device) != target) {
                val accepted = profile.javaClass.getMethod(operation, BluetoothDevice::class.java).invoke(profile, device)
                if (accepted != true) throw refused(platformUnavailable, "bluetooth did not accept the audio operation")
            }
        }
    }

    private suspend fun <T> withProfiles(
        address: String,
        failure: YieldRefusalKind,
        action: suspend (BluetoothDevice, List<AndroidAudioProfiles.ProfileProxyLease>) -> T,
    ): T {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw refused(failure, "bluetooth adapter unavailable")
        val device = adapter.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: throw refused(failure, "headset is not bonded to the phone")
        val leases = mutableListOf<AndroidAudioProfiles.ProfileProxyLease>()
        try {
            requestLeases(adapter, leases, failure)
            return action(device, leases)
        } finally {
            leases.forEach { it.close() }
        }
    }

    private suspend fun requestLeases(
        adapter: BluetoothAdapter,
        leases: MutableList<AndroidAudioProfiles.ProfileProxyLease>,
        failure: YieldRefusalKind,
    ) {
        val ready = withTimeoutOrNull(2_500L) {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
                leases += profiles.requestProfileProxy(adapter, profile)
                    ?: throw refused(failure, "audio profile proxy unavailable")
            }
            true
        }
        if (ready != true) throw refused(failure, "audio profile proxy timed out")
    }

    private fun refused(kind: YieldRefusalKind, detail: String) = DriverException.YieldRefused(kind, detail)
}
