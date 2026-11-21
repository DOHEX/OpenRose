package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

@Suppress("TooGenericExceptionCaught") // Platform sound services report operating-system specific failures.
@Composable
internal fun DesktopOutputRow(sound: DesktopSoundActions, state: ControlState, selection: PrimaryDeviceSelection) {
    val target = selection.primary(state) ?: return
    if (!target.matchesAudioDevice(state.audioDeviceId)) return
    var output by remember(sound) { mutableStateOf<String?>(null) }
    var error by remember(sound) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val visible = LocalCirculationVisible.current
    LaunchedEffect(sound, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            output = try {
                sound.currentOutput()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message
                null
            }
            delay(2_000)
        }
    }
    AppDashboardCard {
        output?.let { AppText("系统默认输出 · $it", role = AppTextRole.Summary) }
        AppButton("打开系统声音设置", {
            scope.launch {
                error = try {
                    sound.openSoundSettings()
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure.message ?: "打开声音设置失败"
                }
            }
        })
        error?.let { AppText(it, role = AppTextRole.Summary) }
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
