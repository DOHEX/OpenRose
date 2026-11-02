package io.github.dohex.openrose

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.core.UserSettings
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.bridge.BridgeWire
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var homeNavigationRequest by mutableLongStateOf(0L)
    private val owner get() = application as OpenRoseApplication
    private val scopeRestarter = IntegrationScopeRestarter()
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        owner.refreshPlatform()
        owner.startBackgroundControlFromUserEntry()
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        owner.refreshPlatform()
    }
    private val associationChooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Whether the user confirmed or cancelled, the platform facts are re-read from the system.
        owner.syncWakeObservation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state by owner.runtime.state.collectAsState()
            val dark = when (state.settings.appearance) {
                Appearance.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(0x801B1B1B.toInt())
                    else SystemBarStyle.light(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()),
                )
            }
            OpenRoseApp(
                state = state,
                onSettingsChange = ::onSettingsChanged,
                onRequestPermission = ::requestBluetoothPermission,
                onOpenSystemSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                onStartScan = owner::startScan,
                onStopScan = owner::stopScan,
                onConnect = owner::connect,
                onDisconnect = owner::disconnect,
                onSet = owner::applySetting,
                onAddDeviceConnect = owner.runtime::connectForAddDevice,
                homeNavigationRequest = homeNavigationRequest,
                onRestartIntegrationScopes = scopeRestarter::restart,
                onRequestNotificationPermission = ::requestNotificationPermission,
                onRequestAssociation = ::requestAssociation,
                onOpenAppSettings = ::openAppSettings,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.action != BridgeWire.ACTION_SHOW_HEADPHONES) return
        val address = intent.getStringExtra(BridgeWire.EXTRA_HEADPHONE_ADDRESS)?.uppercase(Locale.ROOT)
            ?.takeIf(BluetoothAdapter::checkBluetoothAddress)
        // Consume the request so Activity recreation does not retry a failed connection.
        intent.removeExtra(BridgeWire.EXTRA_HEADPHONE_ADDRESS)
        address?.let { owner.connectFromSystem(DeviceId(it)) }
        homeNavigationRequest++
    }

    override fun onResume() {
        super.onResume()
        owner.refreshPlatform()
        owner.startBackgroundControlFromUserEntry()
    }

    override fun onStop() {
        super.onStop()
        // Discovery is a foreground interaction; control ownership and active links stay in the host.
        owner.stopScan()
    }

    /** The system shows its own chooser; the association is the user's explicit consent, never created silently. */
    private fun requestAssociation() {
        owner.requestCompanionAssociation(
            onChooser = { associationChooser.launch(IntentSenderRequest.Builder(it).build()) },
            onFailure = { owner.syncWakeObservation() },
        )
    }

    /** Autostart and battery limits cannot be queried, so the user is sent to the app's system page. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun requestNotificationPermission() {
        val prefs = getSharedPreferences("openrose", MODE_PRIVATE)
        val askable = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!prefs.getBoolean("notificationRequested", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        if (askable) {
            prefs.edit().putBoolean("notificationRequested", true).apply()
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    /** Enabling background control asks for the notification permission once, in context. */
    private fun onSettingsChanged(settings: UserSettings) {
        val enabling = settings.backgroundControlEnabled && !owner.runtime.state.value.settings.backgroundControlEnabled
        owner.updateSettings(settings)
        val prefs = getSharedPreferences("openrose", MODE_PRIVATE)
        if (enabling && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notificationRequested", false)
        ) requestNotificationPermission()
    }

    private fun requestBluetoothPermission() {
        val requested = getSharedPreferences("openrose", MODE_PRIVATE).getBoolean("permissionRequested", false)
        val permanentlyDenied = requested && bluetoothPermissions().any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(it)
        }
        if (permanentlyDenied) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            owner.markPermissionRequested()
            permissionRequest.launch(bluetoothPermissions())
        }
    }
}
