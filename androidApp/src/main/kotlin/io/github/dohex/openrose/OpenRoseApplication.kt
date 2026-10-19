package io.github.dohex.openrose

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.location.LocationManager
import io.github.dohex.openrose.core.ControlSession
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** One owner for the process. Activities and interface styles only observe this session. */
class OpenRoseApplication : Application() {
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var session: ControlSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = ControlSession(
            initialPlatformState = readPlatformState(permissionRequested()),
            transport = AndroidControlTransport(applicationContext),
            scope = controlScope,
            initialSettings = UserSettingsCodec.decode(preferences().all.mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap()),
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Re-read system facts instead of trusting broadcast extras.
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED ||
                    intent.action == LocationManager.MODE_CHANGED_ACTION) refreshPlatform()
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            if (Build.VERSION.SDK_INT <= 30) addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        // Bluetooth broadcasts may originate in a privileged app with a non-system UID.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    fun startScan() { controlScope.launch { session.startScan() } }
    fun stopScan() { controlScope.launch { session.stopScan() } }
    fun connect(deviceId: DeviceId) { controlScope.launch { session.connect(deviceId) } }
    fun disconnect() { controlScope.launch { session.disconnect() } }
    fun applySetting(setting: DeviceSetting, value: FieldValue) {
        controlScope.launch { session.set(setting, value) }
    }

    fun updateSettings(settings: UserSettings) {
        session.updateSettings(settings)
        preferences().edit().also { editor ->
            UserSettingsCodec.encode(settings).forEach { (key, value) -> editor.putString(key, value) }
        }.apply()
    }

    fun refreshPlatform() = session.updatePlatformState(readPlatformState(permissionRequested()))

    fun markPermissionRequested() {
        preferences().edit().putBoolean("permissionRequested", true).apply()
    }

    private fun permissionRequested() = preferences().getBoolean("permissionRequested", false)
    private fun preferences() = getSharedPreferences("openrose", MODE_PRIVATE)
}
