package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Operations from a local, authorised UI to the owner of the LAN endpoint. */
fun interface CirculationClient {
    suspend fun call(fields: Map<String, String>): Map<String, String>
}

private const val REFRESH_MILLIS = 2_000L

internal suspend fun CirculationClient.submit(vararg fields: Pair<String, String>): Map<String, String> = try {
    call(mapOf(*fields))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    mapOf("error" to (failure.localizedMessage ?: "互联服务不可用"))
}

@Composable
private fun rememberPeerList(client: CirculationClient): Map<String, String> {
    var peers by remember(client) { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(client) {
        while (true) {
            peers = client.submit("op" to "list")
            delay(REFRESH_MILLIS)
        }
    }
    return peers
}

@Composable
fun CirculationSettings(
    client: CirculationClient,
    scan: (@Composable ((String) -> Unit) -> Unit)? = null,
    showQr: (@Composable (String) -> Unit)? = null,
) {
    val peers = rememberPeerList(client)
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

    AppGroup(title = "流转配对") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val paired = peers.filterKeys { it.startsWith("paired.") }
            paired.forEach { (key, name) ->
                AppArrowRow(title = name, value = "解除配对", onClick = { submit("op" to "forget", "peer" to key.substringAfter('.')) })
            }
            if (scan != null) {
                scan { qr -> submit("op" to "pairQr", "qr" to qr) }
                val nearby = peers.filterKeys { it.startsWith("near.") && "paired." + it.substringAfter('.') !in paired }
                if (nearby.isEmpty()) AppText("在电脑的流转配对设置中显示配对码", role = AppTextRole.Summary)
                nearby.forEach { (key, name) ->
                    key(key) {
                        CodeEntry(name, busy) { code -> submit("op" to "pair", "peer" to key.substringAfter('.'), "code" to code) }
                    }
                }
            }
            if (showQr != null) {
                AppButton("显示配对码", { submit("op" to "offer") }, enabled = !busy)
                result["code"]?.let { AppText(it, role = AppTextRole.Title) }
                result["qr"]?.let { showQr(it) }
            }
            (result["error"] ?: peers["error"] ?: result["message"])?.let { AppText(it, role = AppTextRole.Summary) }
        }
    }
}

@Composable
private fun CodeEntry(name: String, busy: Boolean, pair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AppText(name, role = AppTextRole.Title)
    BasicTextField(
        value = code,
        onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
        singleLine = true,
        textStyle = TextStyle(color = appColor(AppColorRole.OnSurface)),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        decorationBox = { input ->
            Column {
                AppText("六位配对码", role = AppTextRole.Summary)
                input()
            }
        },
    )
    AppButton("与 $name 配对", { pair(code) }, enabled = !busy && code.length == 6)
}

@Composable
fun RemoteHeadsets(client: CirculationClient) {
    val peers = rememberPeerList(client)
    peers.filterKeys { it.startsWith("paired.") }.forEach { (key, name) ->
        val id = key.substringAfter('.')
        key(client, id) {
            var reading by remember { mutableStateOf(emptyMap<String, String>()) }
            LaunchedEffect(client, id) {
                while (true) {
                    reading = client.submit("op" to "read", "peer" to id)
                    delay(REFRESH_MILLIS)
                }
            }
            RemoteHeadsetCard(name, reading)
        }
    }
}

@Composable
internal fun RemoteHeadsetCard(name: String, reading: Map<String, String>) {
    AppDashboardCard(compact = true) {
        val device = reading["deviceName"]
        if (device == null) {
            val status = when {
                "error" in reading -> "远端不可用"
                reading["status"] == "idle" -> "未持有耳机"
                else -> "正在读取"
            }
            AppText("$name · $status", role = AppTextRole.Summary)
        } else {
            AppText(device, role = AppTextRole.Title)
            AppText("音源设备 · $name", role = AppTextRole.Summary)
            val parts = listOf("左耳" to "left", "右耳" to "right", "充电盒" to "case")
            AppText(parts.joinToString(" · ") { (label, field) ->
                val battery = reading[field]?.toIntOrNull()?.takeIf { it in 0..100 }
                "$label ${battery?.let { "$it%" } ?: "未知"}"
            })
            reading["mode"]?.let { AppText("降噪模式 · ${noiseModeLabel(it)}") }
        }
    }
}
