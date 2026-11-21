package io.github.dohex.openrose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.dohex.openrose.ui.AppButton
import io.github.dohex.openrose.ui.CirculationClient
import io.github.dohex.openrose.ui.CirculationSettings

@Composable
internal fun PhonePairingSettings(client: CirculationClient) {
    CirculationSettings(client, scan = { onResult ->
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onResult) }
        AppButton("扫描电脑配对码", {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑显示的流转配对码"))
        })
    })
}
