package io.github.dohex.openrose.module

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.dohex.openrose.circulation.CirculationNode
import io.github.dohex.openrose.circulation.CirculationService
import io.github.dohex.openrose.circulation.HeadsetCirculation
import io.github.dohex.openrose.circulation.HeadsetEndpoint
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.platform.PhoneCirculationAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

internal fun startPhoneCirculation(context: Context, scope: CoroutineScope, control: ControlRuntime): CirculationService {
    val service = CirculationService()
    scope.launch {
        service.serve {
            val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
            CirculationNode(
                File(context.noBackupFilesDir, "openrose-circulation"),
                name,
                AndroidPeerDiscovery(context),
                HeadsetCirculation(PhoneCirculationAudio(context), control),
                headset = HeadsetEndpoint(control) { scope.isActive },
            )
        }
    }
    return service
}
