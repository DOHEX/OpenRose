package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import io.github.dohex.openrose.circulation.CirculationAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** System audio handoff runs with the module owner's Bluetooth permissions on a peer worker thread. */
@SuppressLint("MissingPermission")
class PhoneCirculationAudio(private val context: Context) : CirculationAudio {
    private val profiles = AndroidAudioProfiles(context)

    override fun isConnected(address: String): Boolean = withProfiles(address) { device, leases ->
        val states = leases.map { it.currentProxy().getConnectionState(device) }
        when {
            BluetoothProfile.STATE_CONNECTED in states -> true
            states.all { it == BluetoothProfile.STATE_DISCONNECTED } -> false
            else -> error("耳机音频连接正在变化，请稍后重试")
        }
    }

    override fun isMediaConnected(address: String): Boolean = withProfiles(address) { device, leases ->
        leases.first().currentProxy().getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
    }

    override fun release(address: String) {
        checkCall()
        change(address, connect = false)
    }

    override fun acquire(address: String) = change(address, connect = true)

    private fun checkCall() {
        val mode = context.getSystemService(AudioManager::class.java).mode
        check(
            mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION &&
                mode != AudioManager.MODE_RINGTONE && context.getSystemService(TelecomManager::class.java)?.isInCall != true,
        ) { "手机通话中，无法让出耳机" }
    }

    private fun change(address: String, connect: Boolean) = withProfiles(address) { device, leases ->
        if (Build.VERSION.SDK_INT >= 37) {
            val status = if (connect) device.connect() else device.disconnect()
            check(status == BluetoothStatusCodes.SUCCESS) { "系统蓝牙拒绝音频操作（$status）" }
        } else {
            // On older releases these are Bluetooth stack profile APIs; the module runs inside that stack.
            val operation = if (connect) "connect" else "disconnect"
            leases.map { it.currentProxy() }.forEach { profile ->
                val target = if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
                if (profile.getConnectionState(device) != target) {
                    val accepted = profile.javaClass.getMethod(operation, BluetoothDevice::class.java).invoke(profile, device)
                    check(accepted == true) { "系统蓝牙未受理音频操作" }
                }
            }
        }
        val observed = withTimeoutOrNull(12_000L) {
            while (true) {
                val states = leases.map { it.currentProxy().getConnectionState(device) }
                // A2DP is the media source. On release, HFP must be gone too.
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
        check(observed == true) { if (connect) "手机接入耳机音频超时" else "手机尚未让出耳机音频" }
    }

    private fun <T> withProfiles(
        address: String,
        action: suspend (BluetoothDevice, List<AndroidAudioProfiles.ProfileProxyLease>) -> T,
    ): T = runBlocking {
        val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) { "系统蓝牙不可用" }
        val device = adapter.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) } ?: error("手机尚未配对这副耳机")
        val leases = mutableListOf<AndroidAudioProfiles.ProfileProxyLease>()
        try {
            val ready = withTimeoutOrNull(2_500L) {
                listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
                    leases += checkNotNull(profiles.requestProfileProxy(adapter, profile)) { "系统音频服务不可用" }
                }
                true
            }
            check(ready == true) { "读取系统音频连接超时" }
            action(device, leases)
        } finally {
            leases.forEach { it.close() }
        }
    }
}
