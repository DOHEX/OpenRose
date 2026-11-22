package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.HandoffStatus
import io.github.dohex.openrose.core.DeviceCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One operation survives the source changing from remote to local while its reply is in flight. */
internal class CirculationActions(private val client: CirculationClient, private val scope: CoroutineScope) {
    private var status by mutableStateOf(HandoffStatus())
    private var mediaAddress by mutableStateOf<String?>(null)
    var target by mutableStateOf<DeviceCandidate?>(null)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val available get() = status.available
    val offline get() = status.offline

    fun hasMedia(headset: DeviceCandidate?): Boolean = headset.hasCirculationAddress(mediaAddress)

    fun isTarget(headset: DeviceCandidate?): Boolean = headset.hasCirculationAddress(target?.circulationAddress)

    fun retainedTarget(bonded: List<DeviceCandidate>): DeviceCandidate? = target?.takeIf { busy || bonded.any(::isTarget) }

    fun pull(headset: DeviceCandidate) {
        val address = headset.circulationAddress ?: return
        if (busy || !available || hasMedia(headset)) return
        target = headset
        feedback = "正在联系音源设备"
        busy = true
        scope.launch {
            try {
                val response = client.pull(address)
                feedback = response.error ?: response.message
            } finally {
                busy = false
            }
        }
    }

    fun dismissResult() {
        if (!busy) {
            target = null
            feedback = null
        }
    }

    suspend fun poll(audioAddress: String) {
        while (true) {
            val pending = target.takeIf { busy }
            val reply = client.handoff(audioAddress)
            currentCoroutineContext().ensureActive()
            mediaAddress = audioAddress.takeIf { reply.mediaReady }
            status = reply
            // Only the request active when this poll started may receive its progress.
            if (pending != null && busy && pending == target) {
                reply.progress?.let { feedback = it }
            }
            delay(if (busy) 250 else VISIBLE_REFRESH_MILLIS)
        }
    }
}

@Composable
internal fun rememberCirculationActions(
    client: CirculationClient?,
    audioTarget: DeviceCandidate?,
    primary: DeviceCandidate?,
    bonded: List<DeviceCandidate>,
): CirculationActions? {
    if (client == null) return null
    val scope = rememberCoroutineScope()
    val actions = remember(client) { CirculationActions(client, scope) }
    // Continue observing a confirmed audio connection even when its transfer feedback has expired.
    val address = actions.target?.circulationAddress ?: audioTarget?.circulationAddress
        ?: bonded.firstOrNull(actions::hasMedia)?.circulationAddress ?: primary?.circulationAddress
        ?: bonded.firstNotNullOfOrNull { it.circulationAddress }
    if (address != null && LocalCirculationVisible.current) {
        LaunchedEffect(actions, address, actions.busy) { actions.poll(address) }
    }
    // Once the user has seen the outcome, the live source and its current action explain the card again.
    LaunchedEffect(actions, actions.target, actions.feedback, actions.busy) {
        if (actions.target != null && !actions.busy) {
            delay(10_000)
            actions.dismissResult()
        }
    }
    return actions
}

@Composable
internal fun HeadsetTransferAction(source: HeadsetCardSource) {
    val feedback = source.transferFeedback
    if (!source.offersTransfer && feedback == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val message = feedback ?: source.transferHint
        message?.let { AppText(it, role = AppTextRole.Footnote) }
        if (source.offersTransfer) {
            AppButton(source.transferLabel, source::pull, enabled = source.transferEnabled, modifier = Modifier.fillMaxWidth())
        }
    }
}

class DesktopSoundActions(val currentOutput: suspend () -> String?, val openSoundSettings: suspend () -> Unit)

@Composable
internal fun DesktopOutputRow(sound: DesktopSoundActions, source: HeadsetCardSource) {
    if (!source.hasLocalAudio) return
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
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText(output?.let { "系统默认输出 · $it" } ?: "系统默认输出", Modifier.weight(1f), role = AppTextRole.Summary)
            TrayTextButton("打开系统声音设置") {
                scope.launch { actionError = soundFailure("打开声音设置失败") { sound.openSoundSettings() } }
            }
        }
        (actionError ?: pollError)?.let { AppText(it, role = AppTextRole.Footnote, color = appColor(AppColorRole.Error)) }
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
