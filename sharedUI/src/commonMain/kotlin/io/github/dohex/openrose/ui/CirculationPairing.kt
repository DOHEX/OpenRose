package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CirculationSettings(
    client: CirculationClient,
    scan: (@Composable ((String) -> Unit) -> (() -> Unit))? = null,
    showQr: (@Composable (String) -> Unit)? = null,
) {
    val peers = rememberPeerList(client)
    val paired = peers.filterKeys { it.startsWith("paired.") }.mapKeys { it.key.removePrefix("paired.") }
    val nearby = peers.filterKeys { it.startsWith("near.") }.mapKeys { it.key.removePrefix("near.") }
    val scope = rememberCoroutineScope()
    var result by remember(client) { mutableStateOf(emptyMap<String, String>()) }
    var busy by remember(client) { mutableStateOf(false) }
    fun submit(vararg fields: Pair<String, String>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = client.submit(*fields)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scan != null) {
                PhonePairing(
                    nearby = nearby - paired.keys,
                    busy = busy,
                    scan = scan,
                    onQr = { submit("op" to "pairQr", "qr" to it) },
                    onPair = { peer, code -> submit("op" to "pair", "peer" to peer, "code" to code) },
                )
            }
            if (showQr != null) {
                DesktopPairing(result["code"], result["qr"], showQr, busy, onOffer = { submit("op" to "offer") })
            }
            val error = result["error"] ?: peers["error"]
            (error ?: result["message"])?.let { message ->
                AppText(
                    message,
                    Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    role = AppTextRole.Summary,
                    color = if (error != null) appColor(AppColorRole.Error) else null,
                )
            }
            if (paired.isNotEmpty()) {
                AppGroup(title = "已配对的设备") {
                    paired.forEach { (id, name) ->
                        key(id) {
                            PairedDeviceRow(name, id in nearby, busy) { submit("op" to "forget", "peer" to id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PairingIntroduction(title: String, summary: String, icon: RoseIcon, action: @Composable () -> Unit) {
    AppDashboardCard(compact = true) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).background(appColor(AppColorRole.SelectedContainer), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(icon, Modifier.size(26.dp), tint = appColor(AppColorRole.SelectedContent))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(title, role = AppTextRole.Title)
                    AppText(summary, role = AppTextRole.Summary)
                }
            }
            action()
        }
    }
}

@Composable
private fun DesktopPairing(code: String?, qr: String?, showQr: @Composable (String) -> Unit, busy: Boolean, onOffer: () -> Unit) {
    if (code == null || qr == null) {
        PairingIntroduction("与手机配对", "让手机和这台电脑连入同一局域网，再用手机扫码。", RoseIcon.LINK) {
            AppButton("显示配对码", onOffer, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    AppDashboardCard(compact = true) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            val instructions: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText("用手机扫码", role = AppTextRole.Title)
                        AppText("在手机 OpenRose 的「流转配对」页点「扫描二维码」。", role = AppTextRole.Summary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText("也可以输入六位配对码", role = AppTextRole.Footnote)
                        val style = when (LocalInterfaceStyle.current) {
                            InterfaceStyle.MIUIX -> MiuixTheme.textStyles.title1
                            InterfaceStyle.MATERIAL3 -> MaterialTheme.typography.headlineLarge
                        }
                        BasicText(
                            code,
                            style = style.copy(
                                color = appColor(AppColorRole.OnSurface),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.12.em,
                            ),
                        )
                    }
                    AppButton("刷新配对码", onOffer, enabled = !busy, kind = AppButtonKind.Secondary)
                }
            }
            val qrTile: @Composable () -> Unit = {
                Box(Modifier.clip(RoundedCornerShape(16.dp))) { showQr(qr) }
            }
            if (maxWidth >= 216.dp + 200.dp * LocalDensity.current.fontScale) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    qrTile()
                    instructions()
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { qrTile() }
                    instructions()
                }
            }
        }
    }
}

@Composable
private fun PairedDeviceRow(name: String, nearby: Boolean, busy: Boolean, onForget: () -> Unit) {
    val availability = if (nearby) "在附近" else "未在附近发现"
    val forget: @Composable () -> Unit = { AppButton("解除配对", onForget, enabled = !busy, kind = AppButtonKind.Secondary) }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(
            title = name,
            summary = availability,
            startAction = { AppIcon(RoseIcon.LINK) },
            endActions = { forget() },
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            headlineContent = { AppText(name) },
            supportingContent = { AppText(availability, role = AppTextRole.Summary) },
            leadingContent = { AppIcon(RoseIcon.LINK) },
            trailingContent = { forget() },
            colors = rowColors(),
        )
    }
}
