package io.github.dohex.openrose

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.core.Appearance

class MainActivity : ComponentActivity() {
    private var homeNavigationRequest by mutableLongStateOf(0L)
    private val owner get() = application as OpenRoseApplication
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        owner.refreshPlatform()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state by owner.session.state.collectAsState()
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
                onSettingsChange = owner::updateSettings,
                onRequestPermission = ::requestBluetoothPermission,
                onOpenSystemSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                onStartScan = owner::startScan,
                onStopScan = owner::stopScan,
                onConnect = owner::connect,
                onDisconnect = owner::disconnect,
                onSet = owner::applySetting,
                onAddDeviceConnect = owner.session::connectForAddDevice,
                homeNavigationRequest = homeNavigationRequest,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.action == ACTION_SHOW_HEADPHONES) homeNavigationRequest++
    }

    override fun onResume() {
        super.onResume()
        owner.refreshPlatform()
    }

    override fun onStop() {
        super.onStop()
        // Discovery is a foreground interaction; control ownership and active links stay in the host.
        owner.stopScan()
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

    companion object {
        const val ACTION_SHOW_HEADPHONES = "io.github.dohex.openrose.SHOW_HEADPHONES"
    }
}
