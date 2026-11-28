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

@SuppressLint("MissingPermission")
class PhoneCirculationAudio(private val context: Context) : LocalAudio {
    private val profiles = AndroidAudioProfiles(context)

    override suspend fun isConnected(address: String): Boolean = withProfiles(address) { device, leases ->
        leases.any { it.currentProxy().getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }
    }

    override suspend fun isMediaConnected(address: String): Boolean = withProfiles(address) { device, leases ->
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
        if (inCall) throw refused(YieldRefusalKind.IN_CALL, "手机通话中，无法让出耳机")
        if (audioManager?.activeRecordingConfigurations?.isNotEmpty() == true) {
            throw refused(YieldRefusalKind.MIC_IN_USE, "麦克风正在使用中，无法让出耳机")
        }
    }

    private suspend fun change(address: String, connect: Boolean) = withProfiles(address) { device, leases ->
        val initialStates = leases.map { it.currentProxy().getConnectionState(device) }
        if (initialStates.any { it == BluetoothProfile.STATE_CONNECTING || it == BluetoothProfile.STATE_DISCONNECTING }) {
            throw refused(YieldRefusalKind.UNAVAILABLE, "耳机音频连接正在变化，请稍后重试")
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
            throw refused(YieldRefusalKind.UNAVAILABLE, if (connect) "手机接入耳机音频超时" else "手机尚未让出耳机音频")
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
                throw refused(YieldRefusalKind.UNAVAILABLE, "系统蓝牙拒绝音频操作（$status）")
            }
            return
        }
        val operation = if (connect) "connect" else "disconnect"
        val target = if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
        leases.map { it.currentProxy() }.forEach { profile ->
            if (profile.getConnectionState(device) != target) {
                val accepted = profile.javaClass.getMethod(operation, BluetoothDevice::class.java).invoke(profile, device)
                if (accepted != true) throw refused(YieldRefusalKind.UNAVAILABLE, "系统蓝牙未受理音频操作")
            }
        }
    }

    private fun systemAdapter(): BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw refused(YieldRefusalKind.UNAVAILABLE, "系统蓝牙不可用")

    private fun BluetoothAdapter.bonded(address: String): BluetoothDevice = bondedDevices.firstOrNull { it.address.equals(address, true) }
        ?: throw refused(YieldRefusalKind.UNAVAILABLE, "手机尚未配对这副耳机")

    private suspend fun <T> withProfiles(
        address: String,
        action: suspend (BluetoothDevice, List<AndroidAudioProfiles.ProfileProxyLease>) -> T,
    ): T {
        val adapter = systemAdapter()
        val device = adapter.bonded(address)
        val leases = mutableListOf<AndroidAudioProfiles.ProfileProxyLease>()
        try {
            val ready = withTimeoutOrNull(2_500L) {
                listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
                    leases += profiles.requestProfileProxy(adapter, profile)
                        ?: throw refused(YieldRefusalKind.UNAVAILABLE, "系统音频服务不可用")
                }
                true
            }
            if (ready != true) throw refused(YieldRefusalKind.UNAVAILABLE, "读取系统音频连接超时")
            return action(device, leases)
        } finally {
            leases.forEach { it.close() }
        }
    }

    private fun refused(kind: YieldRefusalKind, detail: String) = DriverException.YieldRefused(kind, detail)
}
