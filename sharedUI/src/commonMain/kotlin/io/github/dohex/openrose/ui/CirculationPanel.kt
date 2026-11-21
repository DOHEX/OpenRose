package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Only UI polls follow visibility. Bluetooth ownership and the LAN listener run independently. */
val LocalCirculationVisible = staticCompositionLocalOf { true }

internal const val VISIBLE_REFRESH_MILLIS = 2_000L

@Composable
internal fun rememberPeerList(client: CirculationClient, interval: Long = VISIBLE_REFRESH_MILLIS, refresh: Int = 0): PeerDirectory {
    var peers by remember(client) { mutableStateOf(PeerDirectory()) }
    if (LocalCirculationVisible.current) {
        LaunchedEffect(client, interval, refresh) {
            while (true) {
                peers = client.directory()
                delay(interval)
            }
        }
    }
    return peers
}

data class RemoteHeadset(
    val name: String,
    val reading: RemoteReading,
    val busy: Boolean,
    val feedback: String?,
    val selectMode: (String) -> Unit,
) {
    val deviceName: String? get() = reading.deviceName
    val sourceLabel: String get() = "音源设备 · $name"
    val statusLabel: String? get() = when (reading.status) {
        RemoteControlStatus.INITIALIZING -> "已可调降噪，正在读取其他设置"
        RemoteControlStatus.RECOVERING -> "耳机已接入，控制暂不可用"
        RemoteControlStatus.READY -> null
        RemoteControlStatus.UNAVAILABLE -> "远端不可用"
        RemoteControlStatus.IDLE -> "未持有耳机"
        RemoteControlStatus.WAITING -> "正在读取"
    }
}

@Composable
fun RemoteHeadsets(client: CirculationClient) {
    rememberRemoteHeadsets(client).forEach { RemoteHeadsetCard(it) }
}

@Composable
fun rememberRemoteHeadsets(
    client: CirculationClient,
    interval: Long = VISIBLE_REFRESH_MILLIS,
    refresh: Int = 0,
): List<RemoteHeadset> {
    val peers = rememberPeerList(client, interval, refresh)
    return peers.paired.map { (id, name) ->
        key(client, id) { rememberRemoteHeadset(client, id, name, id in peers.nearby, interval, refresh) }
    }
}

@Composable
private fun rememberRemoteHeadset(
    client: CirculationClient,
    id: String,
    name: String,
    nearby: Boolean,
    interval: Long,
    refresh: Int,
): RemoteHeadset {
    if (!nearby) return RemoteHeadset(name, RemoteReading(RemoteControlStatus.UNAVAILABLE), false, null) {}
    val state = remember(client, id) { RemoteHeadsetState(client, id) }
    val scope = rememberCoroutineScope()
    if (LocalCirculationVisible.current) {
        LaunchedEffect(state, state.revision, interval, refresh) { state.poll(interval) }
    }
    return state.presentation(name, scope)
}

private class RemoteHeadsetState(private val client: CirculationClient, private val id: String) {
    private var reading by mutableStateOf(RemoteReading())
    private var feedback by mutableStateOf<Pair<String, String>?>(null)
    private var pending by mutableStateOf<String?>(null)
    var revision by mutableStateOf(0)
        private set

    suspend fun poll(interval: Long) {
        val initialRevision = revision
        while (revision == initialRevision) {
            val reply = client.read(id)
            currentCoroutineContext().ensureActive()
            if (revision != initialRevision) return
            reading = reply
            if (reply.session != null && reply.session != feedback?.first) feedback = null
            delay(interval)
        }
    }

    fun presentation(name: String, scope: CoroutineScope): RemoteHeadset {
        val session = reading.session
        val device = reading.device
        val busy = session != null && session == pending
        val message = when {
            busy -> "等待耳机确认"
            session == null || feedback?.first == session -> feedback?.second
            else -> null
        }
        return RemoteHeadset(name, reading, busy, message) { mode ->
            if (pending != session && session != null && device != null) {
                if (session == reading.session) {
                    pending = session
                    feedback = null
                    scope.launch { select(session, device, mode) }
                }
            }
        }
    }

    private suspend fun select(session: String, device: String, mode: String) {
        val reply = client.setNoise(id, device, session, mode)
        currentCoroutineContext().ensureActive()
        if (pending != session) return
        pending = null
        feedback = reply.error?.let { session to "操作未确认 · $it" }
        if (reading.session == session && reply.session == session) reading = reply
        revision++
    }
}

fun remoteBatteryText(reading: RemoteReading): String = listOf("左耳" to reading.left, "右耳" to reading.right, "充电盒" to reading.case)
    .joinToString(" · ") { (label, percent) -> "$label ${percent?.let { "$it%" } ?: "未知"}" }

@Composable
fun RemoteHeadsetCard(headset: RemoteHeadset) {
    val reading = headset.reading
    AppDashboardCard(compact = true) {
        val device = headset.deviceName
        if (device == null) {
            AppText("${headset.name} · ${headset.statusLabel}", role = AppTextRole.Summary)
        } else {
            AppText(device, role = AppTextRole.Title)
            AppText(headset.sourceLabel, role = AppTextRole.Summary)
            headset.statusLabel?.let { AppText(it, role = AppTextRole.Summary) }
            AppText(remoteBatteryText(reading))
            val mode = reading.mode
            AppText("降噪模式 · ${mode?.let(::noiseModeLabel) ?: "未知"}")
            val modes = reading.modes.sortedBy(::noiseModeOrder)
            if (modes.isNotEmpty()) {
                RemoteNoiseModeChoices(modes, mode, !headset.busy, headset.selectMode)
            }
        }
        headset.feedback?.let { AppText(it, role = AppTextRole.Summary) }
    }
}
