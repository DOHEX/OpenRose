package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.HandoffStatus
import io.github.dohex.openrose.circulation.supportsCirculation
import io.github.dohex.openrose.core.ControlState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CirculationRow(
    client: CirculationClient,
    state: ControlState,
    selection: PrimaryDeviceSelection,
    pullLabel: String,
) {
    val candidate = selection.primary(state)?.takeIf { supportsCirculation(it.profile) } ?: return
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
    if (status.available && !status.mediaReady) {
        CirculationButton(client, address, pullLabel, status, busy) { busy = it }
    }
}

class DesktopSoundActions(val currentOutput: suspend () -> String?, val openSoundSettings: suspend () -> Unit)

@Composable
internal fun DesktopOutputRow(sound: DesktopSoundActions, state: ControlState, selection: PrimaryDeviceSelection) {
    val target = selection.primary(state) ?: return
    if (!target.matchesAudioDevice(state.audioDeviceId)) return
    var output by remember(sound) { mutableStateOf<String?>(null) }
    // The poll and the button report separately, so a good poll does not erase a failed 打开系统声音设置.
    var pollError by remember(sound) { mutableStateOf<String?>(null) }
    var actionError by remember(sound) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val visible = LocalCirculationVisible.current
    LaunchedEffect(sound, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            pollError = soundFailure("读取系统默认输出失败") { output = sound.currentOutput() }
            if (pollError != null) output = null
            delay(2_000)
        }
    }
    AppDashboardCard {
        output?.let { AppText("系统默认输出 · $it", role = AppTextRole.Summary) }
        AppButton("打开系统声音设置", {
            scope.launch { actionError = soundFailure("打开声音设置失败") { sound.openSoundSettings() } }
        })
        (actionError ?: pollError)?.let { AppText(it, role = AppTextRole.Summary) }
    }
}

/** Runs a platform sound call: null when it worked, otherwise the failure's message or [fallback]. */
@Suppress("TooGenericExceptionCaught") // Platform sound services report operating-system specific failures.
private suspend fun soundFailure(fallback: String, call: suspend () -> Unit): String? = try {
    call()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    failure.message ?: fallback
}

@Suppress("TooGenericExceptionCaught") // Platform and transport failures become the card's feedback.
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(label, {
                setBusy(true)
                result = "正在联系音源设备"
                scope.launch {
                    try {
                        val response = client.pull(address)
                        result = response.error ?: response.message
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        result = failure.message ?: "接入失败"
                    } finally {
                        setBusy(false)
                    }
                }
            }, enabled = !busy)
            if (busy) AppBusyIndicator()
        }
    }
}
