package io.github.dohex.openrose

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.scope.IntegrationScopeRestarter
import io.github.dohex.openrose.androidintegration.ui.AndroidPlatformUi
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.platform.bluetoothPermissions
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.ui.model.WindowDestination
import java.util.Locale

internal const val EXTRA_PANEL_DESTINATION = "io.github.dohex.openrose.PANEL_DESTINATION"

class MainActivity : ComponentActivity() {
    private val owner get() = application as OpenRoseApplication
    private val scopeRestarter = IntegrationScopeRestarter(runRoot = ::runRootShell)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        owner.refreshPlatform()
        owner.background.startIfEnabled()
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        owner.refreshPlatform()
    }
    private val associationChooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        owner.background.syncObservation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleNavigationIntent(intent)
        enableEdgeToEdge()
        val platformUi = AndroidPlatformUi(
            integration = owner.runtime.integration,
            onIntegrationSettingsChange = ::onIntegrationSettingsChanged,
            onSelectControlWay = owner::selectControlWay,
            requestBluetoothPermission = ::requestBluetoothPermission,
            openBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            onRequestNotificationPermission = ::requestNotificationPermission,
            onRequestAssociation = ::requestAssociation,
            onOpenAppSettings = ::openAppSettings,
            onRestartScope = scopeRestarter::restart,
            restartScope = lifecycleScope,
            circulationSettings = { PhonePairingSettings(owner.circulation) },
            circulation = owner.circulation,
        )
        setContent {
            val uiSettings by owner.model.uiSettings.collectAsState()
            val dark = when (uiSettings.appearance) {
                Appearance.SYSTEM ->
                    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES

                Appearance.LIGHT -> false

                Appearance.DARK -> true
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(0x801B1B1B.toInt())
                    } else {
                        SystemBarStyle.light(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt())
                    },
                )
            }
            CirculationVisibility {
                OpenRoseApp(model = owner.model, platformUi = platformUi)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.action != BridgeWire.ACTION_SHOW_HEADPHONES) {
            val page = intent.getStringExtra(EXTRA_PANEL_DESTINATION)
            WindowDestination.entries.firstOrNull { it.name == page }?.let(owner.model.navigation::open)
            return
        }
        val address = intent.getStringExtra(BridgeWire.EXTRA_HEADPHONE_ADDRESS)?.uppercase(Locale.ROOT)
            ?.takeIf(BluetoothAdapter::checkBluetoothAddress)
        intent.removeExtra(BridgeWire.EXTRA_HEADPHONE_ADDRESS)
        address?.let { owner.connectFromSystem(DeviceId(it)) }
        intent.getStringExtra(BridgeWire.EXTRA_FALLBACK_REASON)?.takeIf { it.isNotBlank() }?.let { owner.model.notice.value = it }
        intent.removeExtra(BridgeWire.EXTRA_FALLBACK_REASON)
        owner.model.navigation.open(WindowDestination.HEADPHONES)
    }

    override fun onResume() {
        super.onResume()
        owner.refreshPlatform()
        owner.background.startIfEnabled()
    }

    override fun onStop() {
        super.onStop()
        owner.model.stopScan()
    }

    private fun requestAssociation() {
        owner.background.companion.requestAssociation(
            onChooser = { associationChooser.launch(IntentSenderRequest.Builder(it).build()) },
            onFailure = { owner.background.syncObservation() },
        )
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun notificationPermissionMissing(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        val askable = notificationPermissionMissing() &&
            (
                !owner.permissions.notificationRequested ||
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                )
        if (askable) {
            owner.permissions.notificationRequested = true
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    private fun onIntegrationSettingsChanged(settings: IntegrationSettings) {
        val enabling = settings.backgroundControlEnabled && !owner.runtime.integration.value.settings.backgroundControlEnabled
        owner.updateIntegrationSettings(settings)
        if (enabling && notificationPermissionMissing() && !owner.permissions.notificationRequested) {
            requestNotificationPermission()
        }
    }

    private fun requestBluetoothPermission() {
        val requested = owner.permissions.bluetoothRequested
        val permanentlyDenied = requested && bluetoothPermissions().any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(it)
        }
        if (permanentlyDenied) {
            openAppSettings()
        } else {
            owner.permissions.bluetoothRequested = true
            permissionRequest.launch(bluetoothPermissions())
        }
    }
}
