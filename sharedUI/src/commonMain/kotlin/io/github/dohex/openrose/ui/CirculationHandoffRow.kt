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
fun CirculationHandoffRow(client: CirculationClient, state: ControlState, selection: PrimaryDeviceSelection, showOutput: Boolean = false) {
    val candidate = selection.primary(state)?.takeIf { it.profile == ModelProfile.I5_ZT } ?: return
    val address = candidate.classicAddress ?: candidate.deviceId.value
    val connected = state.audioDeviceId?.value in listOf(address, candidate.deviceId.value)
    var status by remember(client, address) { mutableStateOf(emptyMap<String, String>()) }
    var output by remember(client, address, connected) { mutableStateOf<String?>(null) }
    var result by remember(client, address) { mutableStateOf<String?>(null) }
    var busy by remember(client, address) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(client, address, connected, busy) {
        while (true) {
            status = client.submit("op" to "handoff")
            if (showOutput && connected) output = client.submit("op" to "output")["output"]
            delay(if (busy) 250 else 2_000)
        }
    }
    if (connected && showOutput && output != null) {
        AppDashboardCard {
            AppText("系统默认输出 · $output", role = AppTextRole.Summary)
            AppButton("打开系统声音设置", { scope.launch { result = client.submit("op" to "soundSettings")["error"] } })
            result?.let { AppText(it, role = AppTextRole.Summary) }
        }
    } else if (!connected && status["available"] == "true") {
        AppDashboardCard {
            val message = status["progress"] ?: result ?: if (status["offline"] == "true") "对端当前不可达，将尝试本机接入" else null
            message?.let { AppText(it, role = AppTextRole.Summary) }
            AppButton(
                if (state.platform.hostPlatform.isDesktop) "接到这台电脑" else "接回手机",
                {
                    busy = true
                    result = "正在联系音源设备"
                    scope.launch {
                        try {
                            val response = client.submit("op" to "pull", "address" to address)
                            result = response["error"] ?: response["message"]
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            )
        }
    }
}
