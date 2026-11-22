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
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.PeerDirectory
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.RemoteReading
import io.github.dohex.openrose.circulation.RemoteSessionReference
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
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
    val selectMode: (NoiseMode) -> Unit,
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

    /** The peer's name and why no headphones show, for a source that reports none; never ends in "null". */
    val offlineLabel: String get() = listOfNotNull(name, statusLabel).joinToString(" · ")

    /** Battery of the held headphones in the layout of their model; a peer that sends no layout is read as earbuds and case. */
    val batteryText: String
        get() {
            val slots = (reading.batteryLayout ?: BatteryLayout.EARBUDS_AND_CASE).batterySlots()
            return batterySummary(slots, showUnknown = true) { field ->
                when (field) {
                    DeviceField.LEFT_BATTERY -> reading.left
                    DeviceField.RIGHT_BATTERY -> reading.right
                    DeviceField.CASE_BATTERY -> reading.case
                    else -> null
                }
            }.orEmpty()
        }
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
    private var feedback by mutableStateOf<Pair<RemoteSessionReference, String>?>(null)
    private var pending by mutableStateOf<RemoteSessionReference?>(null)
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
        val busy = session != null && session == pending
        val message = when {
            busy -> "等待耳机确认"
            session == null || feedback?.first == session -> feedback?.second
            else -> null
        }
        return RemoteHeadset(name, reading, busy, message) { mode ->
            if (pending != session && session != null) {
                if (session == reading.session) {
                    pending = session
                    feedback = null
                    scope.launch { select(session, mode) }
                }
            }
        }
    }

    private suspend fun select(session: RemoteSessionReference, mode: NoiseMode) {
        val reply = client.setNoise(session, mode)
        currentCoroutineContext().ensureActive()
        if (pending != session) return
        pending = null
        feedback = reply.error?.let { session to "操作未确认 · $it" }
        if (reading.session == session && reply.session == session) reading = reply
        revision++
    }
}

@Composable
fun RemoteHeadsetCard(headset: RemoteHeadset) {
    val reading = headset.reading
    AppDashboardCard(compact = true) {
        val device = headset.deviceName
        if (device == null) {
            AppText(headset.offlineLabel, role = AppTextRole.Summary)
        } else {
            AppText(device, role = AppTextRole.Title)
            AppText(headset.sourceLabel, role = AppTextRole.Summary)
            headset.statusLabel?.let { AppText(it, role = AppTextRole.Summary) }
            AppText(headset.batteryText)
            val mode = reading.mode?.id
            AppText("降噪模式 · ${mode?.let(::noiseModeLabel) ?: "未知"}")
            val modes = reading.modes.map(NoiseMode::id).sortedBy(::noiseModeOrder)
            if (modes.isNotEmpty()) {
                RemoteNoiseModeChoices(modes, mode, !headset.busy) { id -> NoiseMode.fromId(id)?.let(headset.selectMode) }
            }
        }
        headset.feedback?.let { AppText(it, role = AppTextRole.Summary) }
    }
}
