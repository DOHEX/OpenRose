package io.github.dohex.openrose

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import androidx.core.content.edit

internal class AppPreferences(private val context: Context) {
    val unlocked: Boolean get() = context.getSystemService(UserManager::class.java).isUserUnlocked
    fun open(): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var bluetoothRequested: Boolean
        get() = unlocked && open().getBoolean(PERMISSION_REQUESTED, false)
        set(value) {
            if (unlocked) open().edit { putBoolean(PERMISSION_REQUESTED, value) }
        }

    var notificationRequested: Boolean
        get() = unlocked && open().getBoolean(NOTIFICATION_REQUESTED, false)
        set(value) {
            if (unlocked) open().edit { putBoolean(NOTIFICATION_REQUESTED, value) }
        }

    companion object {
        const val FILE = "openrose"
        const val PERMISSION_REQUESTED = "permissionRequested"
        const val NOTIFICATION_REQUESTED = "notificationRequested"
    }
}
