package io.github.dohex.openrose.module.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal const val STATUS_CHANNEL_ID = "openrose_headset_status_v1"
internal const val PROMPT_CHANNEL_ID = "openrose_module_prompt_v1"
internal const val STATUS_NOTIFICATION_ID = 10003
internal const val PRESENTATION_TAG = "OpenRosePresentation"

internal class PresentationNotifications(private val appContext: Context) {
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val available: Boolean get() = notificationManager != null

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("NotificationPermission")
    fun post(tag: String, id: Int, notification: Notification) {
        if (!hasNotificationPermission()) {
            throw SecurityException("Target process has no notification permission")
        }
        val manager = notificationManager ?: throw IllegalStateException("notification_manager_unavailable")
        manager.notify(tag, id, notification)
    }

    fun ensureChannel(id: String, name: String) {
        notificationManager?.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun cancel(tag: String, id: Int) {
        notificationManager?.cancel(tag, id)
    }
}

internal fun Context.bluetoothDevice(address: String): BluetoothDevice? {
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter
    return adapter?.getRemoteDevice(address)
}
