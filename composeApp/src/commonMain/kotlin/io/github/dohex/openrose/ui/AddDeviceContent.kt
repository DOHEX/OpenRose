package io.github.dohex.openrose.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import io.github.dohex.openrose.core.InterfaceStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.DeviceId

/** Presentation only: the route owns scanning, connection attempts, and their lifetimes. */
@Composable
internal fun AddDeviceContent(
    state: ControlState,
    canScan: Boolean,
    selectedDeviceId: DeviceId?,
    connectionPhase: ConnectionPhase?,
    connectionError: ControlError?,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onCancelConnection: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var troubleshootingExpanded by rememberSaveable { mutableStateOf(false) }
    val supported = remember(state.candidates) { state.candidates.filter { it.profile != null } }
    val unsupported = remember(state.candidates) { state.candidates.filter { it.profile == null } }
    val nameCounts = remember(supported) { supported.groupingBy { it.addDeviceName() }.eachCount() }
    val pending = connectionPhase != null
    val scanning = canScan && !pending && state.scan == ScanState.Scanning
    val scanError = (state.scan as? ScanState.Failed)?.error

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            key("search-guidance") {
                if (canScan) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AddDeviceHero(scanning)
                        AppText("打开耳机并靠近设备", role = AppTextRole.Title)
                        AppText("让耳机保持开启，必要时进入配对模式。", role = AppTextRole.Summary)
                    }
                } else {
                    AddDevicePrerequisite(state, onRequestPermission, onOpenSystemSettings)
                }
            }
            if (canScan) {
                key("search-status") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppText(
                            text = when {
                                pending -> "正在连接所选耳机"
                                scanning -> "正在搜索附近的耳机…"
                                scanError != null -> "搜索未完成"
                                supported.isEmpty() -> "暂未找到受支持的耳机"
                                else -> "搜索已结束"
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            role = AppTextRole.Title,
                        )
                        if (!pending && scanError != null && scanError != connectionError) {
                            AppText(scanError.message, color = appColor(AppColorRole.Error))
                        } else if (!scanning && !pending && supported.isEmpty()) {
                            AppText("请打开耳机、进入配对模式并靠近设备，再重新搜索。", role = AppTextRole.Summary)
                        }
                        if (!pending) {
                            AppButton(
                                text = if (scanning) "停止搜索" else "重新搜索",
                                onClick = if (scanning) onStopScan else onStartScan,
                                kind = AppButtonKind.Secondary,
                            )
                        }
                    }
                }
                key("nearby-heading") {
                    AppText("附近设备", Modifier.padding(horizontal = 24.dp), role = AppTextRole.Title)
                }
                supported.forEach { candidate ->
                    key(candidate.deviceId) {
                        AddDeviceCandidateCard(
                            candidate = candidate,
                            distinguishName = (nameCounts[candidate.addDeviceName()] ?: 0) > 1,
                            phase = connectionPhase.takeIf { candidate.deviceId == selectedDeviceId },
                            error = connectionError.takeIf { candidate.deviceId == selectedDeviceId },
                            canConnect = !pending,
                            onConnect = { onConnect(candidate.deviceId) },
                            onCancelConnection = onCancelConnection,
                        )
                    }
                }
            }
            key("troubleshooting") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppButton(
                        text = if (troubleshootingExpanded) "收起排查指引" else "找不到耳机？",
                        onClick = { troubleshootingExpanded = !troubleshootingExpanded },
                        kind = AppButtonKind.Secondary,
                    )
                    if (troubleshootingExpanded) {
                        AppText("排查指引", role = AppTextRole.Title)
                        AppText("1. 打开充电盒，确认耳机已开启且有电。\n2. 按耳机说明进入配对模式。\n3. 将耳机靠近当前设备，然后重新搜索。", role = AppTextRole.Summary)
                        AppButton("打开蓝牙设置", onOpenSystemSettings, kind = AppButtonKind.Secondary)
                        AppButton("诊断详情", onDiagnostics, kind = AppButtonKind.Secondary)
                        if (canScan && supported.isNotEmpty()) {
                            AppText("设备识别信息", role = AppTextRole.Title)
                            supported.forEach { candidate ->
                                AppText(candidate.addDeviceName(), role = AppTextRole.Body)
                                AppText(
                                    listOfNotNull(candidate.profile?.configurationLabel, candidate.rssi?.let { "$it dBm" }).joinToString(" · "),
                                    role = AppTextRole.Summary,
                                )
                            }
                        }
                        if (canScan && unsupported.isNotEmpty()) {
                            AppText("其他蓝牙设备", role = AppTextRole.Title)
                            unsupported.forEach { candidate ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AppText(candidate.name?.takeIf { it.isNotBlank() } ?: "未命名蓝牙设备")
                                    AppText("暂不支持", role = AppTextRole.Summary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDeviceHero(scanning: Boolean) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        if (scanning) {
            val transition = rememberInfiniteTransition(label = "earbud search")
            if (isMaterial3) {
                val expressiveEasing = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1.0f)
                val pulse1 = transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = expressiveEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "search pulse 1",
                )
                val pulse2 = transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 650, easing = expressiveEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "search pulse 2",
                )
                val primary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(200.dp)) {
                    val p1 = pulse1.value
                    val p2 = pulse2.value
                    drawCircle(
                        color = primary.copy(alpha = 0.28f * (1f - p1)),
                        radius = size.minDimension * (0.28f + p1 * 0.22f),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.18f * (1f - p2)),
                        radius = size.minDimension * (0.28f + p2 * 0.22f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.08f * (1f - p1 * 0.4f)),
                        radius = size.minDimension * 0.33f,
                    )
                }
            } else {
                val pulse = transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
                    label = "search pulse",
                )
                val primary = appColor(AppColorRole.Primary)
                Canvas(Modifier.size(184.dp)) {
                    val progress = pulse.value
                    drawCircle(
                        color = primary.copy(alpha = 0.18f * (1f - progress)),
                        radius = size.minDimension * (0.30f + progress * 0.19f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.08f),
                        radius = size.minDimension * 0.34f,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
        EarbudsArtwork(Modifier.widthIn(max = 340.dp))
    }
}

@Composable
private fun AddDevicePrerequisite(
    state: ControlState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    AppDashboardCard {
        when {
            state.platform.permission == PermissionState.REQUIRED || state.platform.permission == PermissionState.DENIED -> {
                AppText("需要蓝牙权限", role = AppTextRole.Title)
                AppText("允许访问附近设备后，才能搜索并连接耳机。", role = AppTextRole.Summary)
                AppButton("授予蓝牙权限", onRequestPermission)
            }
            state.platform.transport == TransportAvailability.Disabled -> {
                AppText("系统蓝牙已关闭", role = AppTextRole.Title)
                AppText("请先在系统设置中打开蓝牙，再回来搜索耳机。", role = AppTextRole.Summary)
                AppButton("打开蓝牙设置", onOpenSystemSettings)
            }
            else -> {
                AppText("蓝牙传输不可用", role = AppTextRole.Title)
                AppText(
                    (state.platform.transport as? TransportAvailability.Unavailable)?.reason ?: "当前蓝牙传输不可用",
                    role = AppTextRole.Summary,
                )
            }
        }
    }
}

@Composable
private fun AddDeviceCandidateCard(
    candidate: DeviceCandidate,
    distinguishName: Boolean,
    phase: ConnectionPhase?,
    error: ControlError?,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onCancelConnection: () -> Unit,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    AppDashboardCard(
        modifier = if (isMaterial3) Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(20.dp),
        ) else Modifier,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val artworkModifier = if (isMaterial3) {
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp),
                    )
            } else {
                Modifier.width(88.dp).height(88.dp)
            }
            Box(artworkModifier, contentAlignment = Alignment.Center) {
                EarbudsArtwork(Modifier.fillMaxWidth().height(88.dp), compact = true)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (isMaterial3) Modifier.padding(start = 12.dp) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(candidate.addDeviceName(), role = AppTextRole.Title)
                if (distinguishName) {
                    AppText("地址末位 ${candidate.deviceId.value.takeLast(8)}", role = AppTextRole.Footnote)
                }
            }
        }
        if (phase != null) {
            if (isMaterial3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AppText(phase.displayText(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, role = AppTextRole.Summary)
                }
            } else {
                AppText(phase.displayText(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, role = AppTextRole.Summary)
            }
            AppButton("取消连接", onCancelConnection, kind = AppButtonKind.Secondary)
        } else {
            error?.let {
                if (isMaterial3) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppText("连接失败", role = AppTextRole.Title, color = MaterialTheme.colorScheme.onErrorContainer)
                        AppText(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                } else {
                    AppText("连接失败", role = AppTextRole.Title)
                    AppText(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = appColor(AppColorRole.Error))
                }
            }
            AppButton(if (error == null) "连接" else "重试", onConnect, enabled = canConnect)
        }
    }
}

private fun DeviceCandidate.addDeviceName(): String =
    name?.takeIf { it.isNotBlank() } ?: profile?.displayName ?: "ROSE 耳机"
