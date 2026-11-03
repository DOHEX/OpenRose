package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.dohex.openrose.core.protocol.ModelProfile

private fun Context.ordinaryOwner() = applicationContext as OpenRoseApplication

/**
 * Manifest receiver for the broadcasts that may revive ordinary background control when no process
 * exists: the first unlock of a boot, and an audio connection of a supported earphone. It only asks
 * the application to start the control service; it never connects anything and every decision about
 * whether to start (saved intent, unlock, permission) stays in the unified runtime state.
 */
class OrdinaryWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> context.ordinaryOwner().wakeOrdinaryControl()
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
                if (isSupportedEarphone(context, intent)) context.ordinaryOwner().wakeOrdinaryControl()
            }
        }
    }

    /** Other Bluetooth audio devices must not wake OpenRose; the name needs the connect permission on Android 12+. */
    @SuppressLint("MissingPermission")
    private fun isSupportedEarphone(context: Context, intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        return ModelProfile.match(device?.name) != null
    }
}

/**
 * Bound by the system when an associated earphone appears (Android 12+), including when the app
 * process does not exist. Presence of a paired device is a wake-up only: the control connection is
 * still driven by the audio connection inside the runtime.
 */
@RequiresApi(Build.VERSION_CODES.S)
class OpenRoseCompanionService : CompanionDeviceService() {
    @Deprecated("Replaced by the association-based callback from Android 13")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) ordinaryOwner().wakeOrdinaryControl()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < 36) ordinaryOwner().wakeOrdinaryControl()
    }

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED || event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) {
            ordinaryOwner().wakeOrdinaryControl()
        }
    }

    // The service only wakes; losing presence never stops or restarts anything.
    @Deprecated("Presence loss is handled by audio disconnection inside the runtime")
    override fun onDeviceDisappeared(address: String) = Unit

    private fun ordinaryOwner() = applicationContext as OpenRoseApplication
}
