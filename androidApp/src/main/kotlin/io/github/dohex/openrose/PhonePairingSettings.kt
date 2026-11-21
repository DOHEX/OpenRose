package io.github.dohex.openrose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.dohex.openrose.ui.CirculationClient
import io.github.dohex.openrose.ui.CirculationSettings

@Composable
internal fun PhonePairingSettings(client: CirculationClient) {
    CirculationSettings(client, scan = { onResult ->
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onResult) }
        val openScanner: () -> Unit = {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑上的流转配对二维码"))
        }
        openScanner
    })
}
