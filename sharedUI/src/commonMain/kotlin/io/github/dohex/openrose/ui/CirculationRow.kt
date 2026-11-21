package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CirculationRow(
    client: CirculationClient,
    state: ControlState,
    selection: PrimaryDeviceSelection,
    pullLabel: String,
    showOutput: Boolean = false,
) {
    val candidate = selection.primary(state)?.takeIf { it.profile == ModelProfile.I5_ZT } ?: return
    val address = candidate.classicAddress ?: candidate.deviceId.value
    val hasAudio = candidate.matchesAudioDevice(state.audioDeviceId)
    var busy by remember(client, address) { mutableStateOf(false) }
    var status by remember(client, address, hasAudio) { mutableStateOf(HandoffStatus(false, false, false, null)) }
    val visible = LocalCirculationVisible.current
    LaunchedEffect(client, address, hasAudio, busy, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            status = client.handoff(address.takeIf { hasAudio })
            delay(if (busy) 250 else 2_000)
        }
    }
    if (hasAudio && showOutput) DesktopOutputRow(client)
    if (status.available && !status.mediaReady) {
        CirculationButton(client, address, pullLabel, status, busy) { busy = it }
    }
}

@Composable
private fun DesktopOutputRow(client: CirculationClient) {
    var output by remember(client) { mutableStateOf<String?>(null) }
    var error by remember(client) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val visible = LocalCirculationVisible.current
    LaunchedEffect(client, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            output = client.output()
            delay(2_000)
        }
    }
    output?.let { name ->
        AppDashboardCard {
            AppText("系统默认输出 · $name", role = AppTextRole.Summary)
            AppButton("打开系统声音设置", { scope.launch { error = client.openSoundSettings().error } })
            error?.let { AppText(it, role = AppTextRole.Summary) }
        }
    }
}

@Composable
private fun CirculationButton(
    client: CirculationClient,
    address: String,
    label: String,
    status: HandoffStatus,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
) {
    var result by remember(client, address) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AppDashboardCard {
        val message = status.progress ?: result ?: if (status.offline) "对端当前不可达，将尝试本机接入" else null
        message?.let { AppText(it, role = AppTextRole.Summary) }
        AppButton(label, {
            setBusy(true)
            result = "正在联系音源设备"
            scope.launch {
                try {
                    val response = client.pull(address)
                    result = response.error ?: response.message
                } finally {
                    setBusy(false)
                }
            }
        }, enabled = !busy)
    }
}
