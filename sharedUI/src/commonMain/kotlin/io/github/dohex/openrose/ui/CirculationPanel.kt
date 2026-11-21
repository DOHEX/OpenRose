package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Operations from a local, authorised UI to the owner of the LAN endpoint. */
fun interface CirculationClient {
    suspend fun call(fields: Map<String, String>): Map<String, String>
}

private const val REFRESH_MILLIS = 2_000L

@Suppress("TooGenericExceptionCaught") // The platform boundary can fail via sockets, local Binder, or an expired pairing.
internal suspend fun CirculationClient.submit(vararg fields: Pair<String, String>): Map<String, String> = try {
    call(mapOf(*fields))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    mapOf("error" to (failure.localizedMessage ?: "互联服务不可用"))
}

@Composable
internal fun rememberPeerList(client: CirculationClient): Map<String, String> {
    var peers by remember(client) { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(client) {
        while (true) {
            peers = client.submit("op" to "list")
            delay(REFRESH_MILLIS)
        }
    }
    return peers
}

data class RemoteHeadset(
    val name: String,
    val reading: Map<String, String>,
    val busy: Boolean,
    val feedback: String?,
    val selectMode: (String) -> Unit,
) {
    val deviceName: String? get() = reading["deviceName"]
    val sourceLabel: String get() = "音源设备 · $name"
    val statusLabel: String? get() = when {
        reading["status"] == "initializing" -> "已可调降噪，正在读取其他设置"
        reading["status"] == "recovering" -> "耳机已接入，控制暂不可用"
        deviceName != null -> null
        "error" in reading -> "远端不可用"
        reading["status"] == "idle" -> "未持有耳机"
        else -> "正在读取"
    }
}

@Composable
fun RemoteHeadsets(client: CirculationClient) {
    rememberRemoteHeadsets(client).forEach { RemoteHeadsetCard(it) }
}

@Composable
fun rememberRemoteHeadsets(client: CirculationClient): List<RemoteHeadset> {
    val peers = rememberPeerList(client)
    return peers.filterKeys { it.startsWith("paired.") }.map { (property, name) ->
        val id = property.substringAfter('.')
        key(client, id) { rememberRemoteHeadset(client, id, name, "near.$id" in peers) }
    }
}

@Composable
private fun rememberRemoteHeadset(client: CirculationClient, id: String, name: String, nearby: Boolean): RemoteHeadset {
    if (!nearby) return RemoteHeadset(name, mapOf("error" to "远端不可用"), false, null) {}
    val state = remember(client, id) { RemoteHeadsetState(client, id) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, state.revision) { state.poll() }
    return state.presentation(name, scope)
}

private class RemoteHeadsetState(private val client: CirculationClient, private val id: String) {
    private var reading by mutableStateOf(emptyMap<String, String>())
    private var feedback by mutableStateOf<Pair<String, String>?>(null)
    private var pending by mutableStateOf<String?>(null)
    var revision by mutableStateOf(0)
        private set

    suspend fun poll() {
        val initialRevision = revision
        while (revision == initialRevision) {
            val reply = client.submit("op" to "read", "peer" to id)
            currentCoroutineContext().ensureActive()
            if (revision != initialRevision) return
            reading = reply
            if (reply["session"] != null && reply["session"] != feedback?.first) feedback = null
            delay(REFRESH_MILLIS)
        }
    }

    fun presentation(name: String, scope: CoroutineScope): RemoteHeadset {
        val session = reading["session"]
        val device = reading["device"]
        val busy = session != null && session == pending
        val message = when {
            busy -> "等待耳机确认"
            session == null || feedback?.first == session -> feedback?.second
            else -> null
        }
        return RemoteHeadset(name, reading, busy, message) { mode ->
            if (pending != session && session != null && device != null) {
                if (session == reading["session"]) {
                    pending = session
                    feedback = null
                    scope.launch { select(session, device, mode) }
                }
            }
        }
    }

    private suspend fun select(session: String, device: String, mode: String) {
        val reply = client.submit("op" to "noise", "peer" to id, "device" to device, "session" to session, "mode" to mode)
        currentCoroutineContext().ensureActive()
        if (pending != session) return
        pending = null
        feedback = reply["error"]?.let { session to "操作未确认 · $it" }
        if (reading["session"] == session && reply["session"] == session) reading = reply
        revision++
    }
}

fun remoteBatteryText(reading: Map<String, String>): String {
    val parts = listOf("左耳" to "left", "右耳" to "right", "充电盒" to "case")
    return parts.joinToString(" · ") { (label, field) ->
        val battery = reading[field]?.toIntOrNull()?.takeIf { it in 0..100 }
        "$label ${battery?.let { "$it%" } ?: "未知"}"
    }
}

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
            val mode = reading["mode"]
            AppText("降噪模式 · ${mode?.let(::noiseModeLabel) ?: "未知"}")
            val modes = reading["modes"].orEmpty().split(',').filter(String::isNotEmpty).sortedBy(::noiseModeOrder)
            if (modes.isNotEmpty()) {
                RemoteNoiseModeChoices(modes, mode, !headset.busy, headset.selectMode)
            }
        }
        headset.feedback?.let { AppText(it, role = AppTextRole.Summary) }
    }
}
