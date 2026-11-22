package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.CirculationResult
import io.github.dohex.openrose.circulation.PairingTicket
import io.github.dohex.openrose.circulation.PeerDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CirculationSettings(
    client: CirculationClient,
    scan: (@Composable ((String) -> Unit) -> (() -> Unit))? = null,
    showQr: (@Composable (String) -> Unit)? = null,
) {
    var refresh by remember(client) { mutableIntStateOf(0) }
    val peers = rememberPeerList(client, refresh = refresh)
    val scope = rememberCoroutineScope()
    var result by remember(client) { mutableStateOf<CirculationResult?>(null) }
    var issued by remember(client) { mutableStateOf<PairingTicket?>(null) }
    var busy by remember(client) { mutableStateOf(false) }

    @Suppress("TooGenericExceptionCaught") // Whatever the platform throws becomes the page's feedback.
    fun submit(action: suspend () -> CirculationResult) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = action()
                refresh++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                result = CirculationResult(error = failure.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }

    val live = rememberLiveTicket(peers, issued)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scan != null) {
                PhonePairing(
                    nearby = peers.nearby - peers.paired.keys,
                    busy = busy,
                    scan = scan,
                    onQr = { qr -> submit { client.pairQr(qr) } },
                    onPair = { peer, code -> submit { client.pair(peer, code) } },
                )
            }
            if (showQr != null) {
                DesktopPairing(live.ticket, live.remaining, issued != null, showQr, busy) {
                    submit {
                        val reply = client.offer()
                        issued = reply.ticket
                        CirculationResult(null, reply.error)
                    }
                }
            }
            val error = result?.error ?: peers.error
            (error ?: result?.message)?.let { message ->
                AppText(
                    message,
                    Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    role = AppTextRole.Summary,
                    color = if (error != null) appColor(AppColorRole.Error) else null,
                )
            }
            if (peers.paired.isNotEmpty()) {
                AppGroup(title = "已配对的设备") {
                    peers.paired.forEach { (id, name) ->
                        key(id) {
                            PairedDeviceRow(name, id in peers.nearby, busy) { submit { client.forget(id) } }
                        }
                    }
                }
            }
        }
    }
}

/** The offer peers still accept, with the time it has left; the page hides it and counts down on this one clock. */
private class LiveTicket(val ticket: PairingTicket?, val remaining: LongState)

@Composable
private fun rememberLiveTicket(peers: PeerDirectory, issued: PairingTicket?): LiveTicket {
    val offer = peers.offer.takeIf { peers.error == null }
    val remaining = remember(offer?.qr, issued) { mutableLongStateOf(offer?.validForMillis ?: 0L) }
    LaunchedEffect(remaining) {
        if (offer != null) {
            var left = offer.validForMillis
            while (left > 0) {
                remaining.longValue = left
                val step = minOf(left, COUNTDOWN_STEP_MILLIS)
                delay(step)
                left -= step
            }
            remaining.longValue = 0L
        }
    }
    // Only the flip to expired recomposes the page; the seconds are read where they are shown.
    val live by remember(remaining) { derivedStateOf { remaining.longValue > 0 } }
    return LiveTicket(offer.takeIf { live }, remaining)
}

private const val COUNTDOWN_STEP_MILLIS = 1_000L

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
private fun DesktopPairing(
    ticket: PairingTicket?,
    remaining: LongState,
    previouslyShown: Boolean,
    showQr: @Composable (String) -> Unit,
    busy: Boolean,
    onOffer: () -> Unit,
) {
    if (ticket == null) {
        val summary = if (previouslyShown) "上一组配对码已失效，需要配对时请重新获取。" else "让手机和这台电脑连入同一局域网，再用手机扫码。"
        PairingIntroduction("与手机配对", summary, RoseIcon.LINK) {
            AppButton(if (previouslyShown) "刷新配对码" else "显示配对码", onOffer, enabled = !busy, modifier = Modifier.fillMaxWidth())
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
                            ticket.code,
                            style = style.copy(
                                color = appColor(AppColorRole.OnSurface),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.12.em,
                            ),
                        )
                        AppText("约 ${(remaining.longValue + 999) / 1000} 秒后失效", role = AppTextRole.Footnote)
                    }
                    AppButton("刷新配对码", onOffer, enabled = !busy, kind = AppButtonKind.Secondary)
                }
            }
            val qrTile: @Composable () -> Unit = {
                Box(Modifier.clip(RoundedCornerShape(16.dp))) { showQr(ticket.qr) }
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
    var confirming by remember { mutableStateOf(false) }
    val availability = if (nearby) "在附近" else "未在附近发现"
    val forget: @Composable () -> Unit = { AppButton("解除配对", { confirming = true }, enabled = !busy, kind = AppButtonKind.Secondary) }
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
    if (confirming) {
        ForgetPeerDialog(name, onConfirm = {
            confirming = false
            onForget()
        }, onDismiss = { confirming = false })
    }
}

/** Forgetting a pairing cuts the peer off from this device's headphones until both sides pair again. */
@Composable
private fun ForgetPeerDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = "解除与「$name」的配对？"
    val message = "解除后，这台设备与「$name」之间的耳机流转和远程控制将失效，需要重新配对才能恢复。"
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> OverlayDialog(show = true, title = title, onDismissRequest = onDismiss) {
            AppText(message, role = AppTextRole.Summary)
            AppButton("确认解除", onConfirm, modifier = Modifier.fillMaxWidth())
            AppButton("取消", onDismiss, kind = AppButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }

        InterfaceStyle.MATERIAL3 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("确认解除") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}
