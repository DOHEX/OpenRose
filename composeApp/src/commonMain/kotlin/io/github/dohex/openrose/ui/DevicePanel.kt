package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.transport.DeviceId

@Composable
internal fun DevicePanel(
    state: ControlState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
) {
    val canScan = state.platform.transport == TransportAvailability.Available &&
        state.platform.permission in setOf(PermissionState.GRANTED, PermissionState.NOT_REQUIRED)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AppCard {
            AppText("我的耳机", title = true)
            Spacer(Modifier.height(20.dp))
            when (val connection = state.connection) {
                ConnectionState.Disconnected -> {
                    AppText("未连接耳机", title = true)
                    Spacer(Modifier.height(8.dp))
                    AppText("扫描附近设备，连接已识别的型号配置。", secondary = true)
                    Spacer(Modifier.height(16.dp))
                    ScanControls(state.scan, canScan, onStartScan, onStopScan)
                    if (!canScan) {
                        Spacer(Modifier.height(8.dp))
                        AppText("需要蓝牙权限和可用的蓝牙传输才能扫描。", secondary = true)
                    }
                    if (state.candidates.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        AppText("附近设备", title = true)
                        state.candidates.sortedWith(
                            compareByDescending<DeviceCandidate> { it.profile != null }
                                .thenBy { it.deviceId.value },
                        ).forEach { candidate ->
                            Spacer(Modifier.height(16.dp))
                            CandidateRow(candidate, canScan, onConnect)
                        }
                    } else if (state.scan == ScanState.Scanning) {
                        Spacer(Modifier.height(16.dp))
                        AppText("尚未发现设备，请确认耳机已开启并在附近。", secondary = true)
                    }
                }
                is ConnectionState.Connecting -> {
                    AppText(state.candidates.find { it.deviceId == connection.deviceId }?.name ?: "正在连接耳机", title = true)
                    Spacer(Modifier.height(8.dp))
                    AppText(connection.phase.displayText(), secondary = true)
                    Spacer(Modifier.height(16.dp))
                    AppButton("取消连接", onDisconnect)
                }
                is ConnectionState.Connected -> {
                    AppText(connection.profile.displayName, title = true)
                    Spacer(Modifier.height(8.dp))
                    AppText(connection.phase.displayText(), secondary = true)
                    Spacer(Modifier.height(16.dp))
                    AppButton("断开连接", onDisconnect)
                }
            }
            state.lastError?.let { error ->
                if ((state.scan as? ScanState.Failed)?.error != error) {
                    Spacer(Modifier.height(16.dp))
                    AppText("状态提示：${error.message}", secondary = true)
                }
            }
        }
        state.device?.takeIf { device -> state.connection.matches(device) }?.let { device ->
            DeviceReadings(device, state.connection.phaseOrNull() == ConnectionPhase.INITIALIZING)
        }
    }
}

@Composable
private fun ScanControls(
    scan: ScanState,
    canScan: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    when (scan) {
        ScanState.Scanning -> {
            AppText("正在扫描附近设备…", secondary = true)
            Spacer(Modifier.height(8.dp))
            AppButton("停止扫描", onStop)
        }
        ScanState.Idle -> AppButton("扫描耳机", onStart, enabled = canScan)
        is ScanState.Failed -> {
            AppText("扫描未完成：${scan.error.message}", secondary = true)
            Spacer(Modifier.height(8.dp))
            AppButton("重新扫描", onStart, enabled = canScan)
        }
    }
}

@Composable
private fun CandidateRow(candidate: DeviceCandidate, canConnect: Boolean, onConnect: (DeviceId) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(candidate.name?.takeIf { it.isNotBlank() } ?: "未命名蓝牙设备")
        AppText(candidate.profile?.let { "已识别：${it.displayName}" } ?: "未知型号配置，无法连接", secondary = true)
        AppText(
            candidate.deviceId.value + (candidate.rssi?.let { " · $it dBm" } ?: ""),
            secondary = true,
        )
        if (candidate.profile != null) {
            Spacer(Modifier.height(4.dp))
            AppButton("连接", { onConnect(candidate.deviceId) }, enabled = canConnect)
        }
    }
}

@Composable
private fun DeviceReadings(device: DeviceState, initializing: Boolean) {
    var showDetails by remember(device.sessionId) { mutableStateOf(false) }
    AppCard {
        AppText("设备状态", title = true)
        Spacer(Modifier.height(8.dp))
        AppText(if (initializing) "正在读取设备状态…" else "按设备实际返回的状态显示。", secondary = true)
        Spacer(Modifier.height(16.dp))
        device.capabilities.readableFields.sortedBy { it.ordinal }.forEach { field ->
            FieldRow(field.displayName, readingText(field, device.fields[field], initializing, device.fieldErrors[field]))
        }
        if (device.capabilities.writableSettings.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            AppText("当前提供状态读取，控制设置尚未开放。", secondary = true)
        }
        if (device.warnings.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            AppText("读取提示")
            device.warnings.distinct().forEach { AppText(it, secondary = true) }
        }
        Spacer(Modifier.height(16.dp))
        AppButton(if (showDetails) "收起原始记录" else "查看原始记录", { showDetails = !showDetails }, choice = true)
        if (showDetails) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText("会话 ${device.sessionId} · ${device.deviceId.value}", secondary = true)
                    device.fields.forEach { (field, reading) ->
                        if (reading.rawHex.isNotEmpty()) AppText("${field.displayName}：${reading.rawHex}", secondary = true)
                    }
                    if (device.rawNotifications.isNotEmpty()) {
                        AppText("最近收到的数据")
                        device.rawNotifications.forEach { AppText(it, secondary = true) }
                    }
                    if (device.unknownNotifications.isNotEmpty()) {
                        AppText("未解释的数据")
                        device.unknownNotifications.forEach { AppText(it, secondary = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppText(label, Modifier.weight(1f), secondary = true)
        AppText(value, Modifier.weight(1.4f))
    }
}

private fun readingText(
    field: DeviceField,
    reading: FieldReading?,
    initializing: Boolean,
    error: ControlError?,
): String = when (reading) {
    null, FieldReading.Loading -> when {
        error?.code == ControlErrorCode.TIMEOUT -> "读取超时"
        error != null -> "读取失败：${error.message}"
        initializing -> "读取中…"
        else -> "尚未读取"
    }
    is FieldReading.NotProvided -> "设备未提供"
    is FieldReading.Unknown -> "未知"
    is FieldReading.Known -> displayFieldValue(field, reading.value)
}

private fun ConnectionState.phaseOrNull(): ConnectionPhase? = when (this) {
    ConnectionState.Disconnected -> null
    is ConnectionState.Connecting -> phase
    is ConnectionState.Connected -> phase
}

private fun ConnectionState.matches(device: DeviceState): Boolean = when (this) {
    ConnectionState.Disconnected -> false
    is ConnectionState.Connecting -> deviceId == device.deviceId && sessionId == device.sessionId
    is ConnectionState.Connected -> deviceId == device.deviceId && sessionId == device.sessionId
}

private fun ConnectionPhase.displayText(): String = when (this) {
    ConnectionPhase.CONNECTING -> "正在建立蓝牙连接…"
    ConnectionPhase.DISCOVERING -> "正在发现耳机服务…"
    ConnectionPhase.SUBSCRIBING -> "正在订阅状态通知…"
    ConnectionPhase.INITIALIZING -> "正在初始化并读取状态…"
    ConnectionPhase.READY -> "已连接 · 可以读取状态"
}
