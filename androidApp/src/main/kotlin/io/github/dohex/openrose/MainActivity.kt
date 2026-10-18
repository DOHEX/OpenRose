package io.github.dohex.openrose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.dohex.openrose.ui.OpenRoseApp

class MainActivity : ComponentActivity() {
    private val owner get() = application as OpenRoseApplication
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        owner.refreshPlatform()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by owner.session.state.collectAsState()
            OpenRoseApp(
                state = state,
                onSettingsChange = owner::updateSettings,
                onRequestPermission = ::requestBluetoothPermission,
                onOpenSystemSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        owner.refreshPlatform()
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
