package io.github.dohex.openrose.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.dohex.openrose.circulation.CirculationNode
import io.github.dohex.openrose.circulation.CirculationService
import io.github.dohex.openrose.circulation.HeadsetCirculation
import io.github.dohex.openrose.circulation.HeadsetEndpoint
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.ui.CirculationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress

private const val DESKTOP_CIRCULATION_PORT = 21871

internal fun desktopCirculation(scope: CoroutineScope, control: ControlRuntime, audio: DesktopCirculationAudio?): CirculationClient {
    val service = CirculationService()
    val headset = HeadsetEndpoint(control)
    scope.launch {
        service.serve(DESKTOP_CIRCULATION_PORT) {
            val name = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: InetAddress.getLocalHost().hostName
            CirculationNode(
                File(System.getProperty("user.home"), ".openrose/circulation"),
                name,
                DesktopPeerDiscovery(),
                audio?.let { HeadsetCirculation(it, control) },
                handler = headset::handle,
            )
        }
    }
    return CirculationClient { fields ->
        withContext(Dispatchers.IO) {
            when (fields["op"]) {
                "output" -> audio?.currentOutput()?.let { mapOf("output" to it) }.orEmpty()

                "soundSettings" -> {
                    audio?.openSoundSettings()
                    emptyMap()
                }

                else -> service.call(fields)
            }
        }
    }
}

@Composable
internal fun PairingQr(text: String) {
    val qr = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1) }
    Canvas(Modifier.size(192.dp).background(Color.White).semantics { contentDescription = "流转配对二维码" }) {
        val cell = (size.minDimension / qr.width).toInt().coerceAtLeast(1).toFloat()
        val margin = ((size.minDimension - qr.width * cell) / 2).toInt()
        for (y in 0 until qr.height) {
            for (x in 0 until qr.width) {
                if (qr[x, y]) drawRect(Color.Black, Offset(margin + x * cell, margin + y * cell), Size(cell, cell))
            }
        }
    }
}
