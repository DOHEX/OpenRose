@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.dohex.openrose.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionBlockReason
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

@Composable
internal fun AddDeviceContent(
    state: ControlState,
    canScan: Boolean,
    selectedDeviceId: DeviceId?,
    connectionPhase: ConnectionPhase?,
    connectionError: ControlError?,
    startScanError: ControlError?,
    onRequestPermission: (() -> Unit)?,
    onOpenSystemSettings: (() -> Unit)?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onCancelConnection: () -> Unit,
    onDiagnostics: () -> Unit,
    scanTimedOut: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val supported = remember(state.candidates) { state.candidates.filter { it.profile != null } }
    val unsupported = remember(state.candidates) { state.candidates.filter { it.profile == null } }
    val nameCounts = remember(supported) { supported.groupingBy { it.addDeviceName() }.eachCount() }
    val pending = connectionPhase != null
    val scanning = canScan && !pending && state.scan == ScanState.Scanning
    val scanError = startScanError ?: (state.scan as? ScanState.Failed)?.error
    val showEmptyHero = canScan && supported.isEmpty() && selectedDeviceId == null
    val reduceMotion = rememberReduceMotion()
    val sizeSpec: FiniteAnimationSpec<IntSize> = when {
        reduceMotion -> snap()
        LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3 -> MaterialTheme.motionScheme.defaultSpatialSpec()
        else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .animateContentSize(animationSpec = sizeSpec),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            key("search-stage") {
                when {
                    !canScan -> AddDevicePrerequisite(state, onRequestPermission, onOpenSystemSettings)

                    showEmptyHero -> AddDeviceRadarHero(
                        scanning = scanning,
                        scanTimedOut = scanTimedOut,
                        scanError = scanError,
                        onStartScan = onStartScan,
                        onStopScan = onStopScan,
                    )

                    else -> AddDeviceCompactStatusHeader(
                        count = supported.size,
                        pending = pending,
                        scanning = scanning,
                        scanTimedOut = scanTimedOut,
                        scanError = scanError,
                        connectionError = connectionError,
                        onStartScan = onStartScan,
                        onStopScan = onStopScan,
                    )
                }
            }
            if (canScan && supported.isNotEmpty()) {
                AddDeviceCandidateGroup(
                    supported = supported,
                    nameCounts = nameCounts,
                    selectedDeviceId = selectedDeviceId,
                    connectionPhase = connectionPhase,
                    connectionError = connectionError,
                    pending = pending,
                    onConnect = onConnect,
                    onCancelConnection = onCancelConnection,
                )
            }
            AddDeviceTroubleshooting(canScan, supported, unsupported, onOpenSystemSettings, onDiagnostics)
        }
    }
}

private const val STILL_PULSE = 0.4f

@Composable
private fun AddDeviceRadarHero(
    scanning: Boolean,
    scanTimedOut: Boolean,
    scanError: ControlError?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val edgePadding = if (isMaterial3) 16.dp else 12.dp
    val statusText = searchStatusText(
        count = 0,
        pending = false,
        scanning = scanning,
        scanTimedOut = scanTimedOut,
        scanError = scanError,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = edgePadding)
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AddDeviceRadarVisual(scanning)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText("打开耳机并靠近设备", role = AppTextRole.Title, textAlign = TextAlign.Center)
            AppText(
                text = "让耳机保持开启，必要时进入配对模式。",
                role = AppTextRole.Summary,
                textAlign = TextAlign.Center,
            )
        }
        AddDeviceStatusPill(
            text = statusText,
            busy = scanning,
            isError = scanError != null,
        )
        if (scanError != null) {
            AddDeviceInlineNotice(
                message = scanError.message,
                isError = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(2.dp))
        AppButton(
            text = if (scanning) "停止搜索" else "重新搜索",
            onClick = if (scanning) onStopScan else onStartScan,
            kind = if (scanning) AppButtonKind.Secondary else AppButtonKind.Primary,
        )
    }
}

@Composable
private fun AddDeviceRadarVisual(scanning: Boolean) {
    val reduceMotion = rememberReduceMotion()
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val backdropColor = if (isMaterial3) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (scanning) 0.24f else 0.16f)
    } else {
        MiuixTheme.colorScheme.secondaryContainer.copy(alpha = if (scanning) 0.55f else 0.35f)
    }
    Box(Modifier.fillMaxWidth().height(184.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(backdropColor),
        )
        if (scanning) {
            if (isMaterial3) {
                val pulse1 = remember(reduceMotion) { Animatable(if (reduceMotion) STILL_PULSE else 0f) }
                val pulse2 = remember(reduceMotion) { Animatable(if (reduceMotion) STILL_PULSE else 0f) }
                LaunchedEffect(reduceMotion) {
                    if (reduceMotion) return@LaunchedEffect
                    launch {
                        while (isActive) {
                            pulse1.snapTo(0f)
                            pulse1.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessVeryLow,
                                    visibilityThreshold = 0.001f,
                                ),
                            )
                        }
                    }
                    launch {
                        delay(650)
                        while (isActive) {
                            pulse2.snapTo(0f)
                            pulse2.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessVeryLow,
                                    visibilityThreshold = 0.001f,
                                ),
                            )
                        }
                    }
                }
                val primary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(184.dp)) {
                    val p1 = pulse1.value.coerceIn(0f, 1f)
                    val p2 = pulse2.value.coerceIn(0f, 1f)
                    drawCircle(
                        color = primary.copy(alpha = 0.28f * (1f - p1)),
                        radius = size.minDimension * (0.30f + p1 * 0.20f),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.18f * (1f - p2)),
                        radius = size.minDimension * (0.30f + p2 * 0.20f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            } else {
                val pulse: State<Float> = if (reduceMotion) {
                    rememberUpdatedState(STILL_PULSE)
                } else {
                    rememberInfiniteTransition(label = "earbud search").animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
                        label = "search pulse",
                    )
                }
                val primary = MiuixTheme.colorScheme.primary
                Canvas(Modifier.size(184.dp)) {
                    val progress = pulse.value
                    drawCircle(
                        color = primary.copy(alpha = 0.22f * (1f - progress)),
                        radius = size.minDimension * (0.30f + progress * 0.19f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.35f,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
        EarbudsArtwork(Modifier.widthIn(max = 320.dp))
    }
}

@Composable
private fun AddDeviceStatusPill(
    text: String,
    busy: Boolean,
    isError: Boolean,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val containerColor = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        isError -> MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
        isMaterial3 -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MiuixTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.onErrorContainer
        isError -> MiuixTheme.colorScheme.error
        isMaterial3 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MiuixTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            AppBusyIndicator(14.dp, contentDescription = text)
        } else if (isError) {
            AppIcon(RoseIcon.ERROR, Modifier.size(15.dp), tint = contentColor)
        }
        AppText(text, role = AppTextRole.Footnote, color = contentColor)
    }
}

@Composable
private fun AddDeviceCompactStatusHeader(
    count: Int,
    pending: Boolean,
    scanning: Boolean,
    scanTimedOut: Boolean,
    scanError: ControlError?,
    connectionError: ControlError?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    val showScanError = !pending && scanError != null && scanError != connectionError
    val statusText = searchStatusText(count, pending, scanning, scanTimedOut, scanError)
    AddDeviceCompactStatusSurface(isError = showScanError) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddDeviceStatusLeadingBadge(busy = scanning || pending, isError = showScanError)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (count > 0) {
                    AppText("已找到 $count 副耳机", role = AppTextRole.Title)
                }
                AppText(
                    text = statusText,
                    role = if (count == 0) AppTextRole.Title else AppTextRole.Summary,
                )
            }
            if (!pending) {
                AddDeviceCompactActionButton(
                    text = if (scanning) "停止搜索" else "重新搜索",
                    primary = false,
                    enabled = true,
                    onClick = if (scanning) onStopScan else onStartScan,
                )
            }
        }
        if (showScanError) {
            AppText(
                text = scanError.message,
                role = AppTextRole.Footnote,
                color = errorContentColor(),
            )
        }
    }
}

@Composable
private fun AddDeviceCompactStatusSurface(
    isError: Boolean,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val edgePadding = if (isMaterial3) 16.dp else 12.dp
    val containerColor = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        isError -> MiuixTheme.colorScheme.error.copy(alpha = 0.10f)
        isMaterial3 -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    val shape = if (isMaterial3) MaterialTheme.shapes.largeIncreased else RoundedCornerShape(16.dp)
    val borderModifier = if (isMaterial3) {
        Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            shape = shape,
        )
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = edgePadding)
            .clip(shape)
            .background(containerColor)
            .then(borderModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun errorContentColor() = if (LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3) {
    MaterialTheme.colorScheme.onErrorContainer
} else {
    MiuixTheme.colorScheme.error
}

@Composable
private fun AddDeviceStatusLeadingBadge(busy: Boolean, isError: Boolean) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val badgeBg = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.errorContainer
        isError -> MiuixTheme.colorScheme.error.copy(alpha = 0.14f)
        isMaterial3 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        else -> MiuixTheme.colorScheme.secondaryContainer
    }
    val tint = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.onErrorContainer
        isError -> MiuixTheme.colorScheme.error
        else -> appColor(AppColorRole.Primary)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(badgeBg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> AppBusyIndicator(18.dp)
            isError -> AppIcon(RoseIcon.ERROR, Modifier.size(18.dp), tint = tint)
            else -> AppIcon(RoseIcon.BLUETOOTH, Modifier.size(18.dp), tint = tint)
        }
    }
}

private data class PrerequisiteSpec(
    val title: String,
    val summary: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val isUnavailable: Boolean = false,
)

private fun prerequisiteSpec(
    state: ControlState,
    onRequestPermission: (() -> Unit)?,
    onOpenSystemSettings: (() -> Unit)?,
): PrerequisiteSpec {
    val blockReason = state.connectionBlockReason
    return when {
        blockReason == ConnectionBlockReason.BluetoothPermissionRequired -> PrerequisiteSpec(
            title = "需要蓝牙权限",
            summary = "允许访问附近设备后，才能搜索并连接耳机。",
            actionLabel = "授予蓝牙权限",
            onAction = onRequestPermission,
        )

        state.platform.scanPermission.missing -> PrerequisiteSpec(
            title = "搜索耳机需要定位权限",
            summary = if (onRequestPermission == null) {
                "当前宿主未获得扫描权限，可在设置中切回应用控制后搜索。"
            } else {
                "此 Android 版本需要定位权限来扫描附近耳机。已配对耳机仍可直接连接。"
            },
            actionLabel = "授予定位权限",
            onAction = onRequestPermission,
        )

        blockReason == ConnectionBlockReason.BluetoothDisabled -> PrerequisiteSpec(
            title = "系统蓝牙已关闭",
            summary = "请先在系统设置中打开蓝牙，再回来搜索耳机。",
            actionLabel = "打开蓝牙设置",
            onAction = onOpenSystemSettings,
        )

        else -> PrerequisiteSpec(
            title = "蓝牙传输不可用",
            summary = (blockReason as? ConnectionBlockReason.TransportUnavailable)
                ?.displayText(state.platform.hostPlatform)
                ?: "当前蓝牙传输不可用",
            isUnavailable = true,
        )
    }
}

@Composable
private fun AddDevicePrerequisite(
    state: ControlState,
    onRequestPermission: (() -> Unit)?,
    onOpenSystemSettings: (() -> Unit)?,
) {
    val spec = prerequisiteSpec(state, onRequestPermission, onOpenSystemSettings)
    AppDashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AddDevicePrerequisiteIcon(spec.isUnavailable)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(spec.title, role = AppTextRole.Title)
                AppText(spec.summary, role = AppTextRole.Summary)
            }
        }
        val actionLabel = spec.actionLabel
        val onAction = spec.onAction
        if (actionLabel != null && onAction != null) {
            AppButton(actionLabel, onAction, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AddDevicePrerequisiteIcon(isUnavailable: Boolean) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val badgeColor = when {
        isUnavailable && isMaterial3 -> MaterialTheme.colorScheme.errorContainer
        isUnavailable -> MiuixTheme.colorScheme.error.copy(alpha = 0.14f)
        isMaterial3 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MiuixTheme.colorScheme.secondaryContainer
    }
    val iconTint = when {
        isUnavailable -> errorContentColor()
        isMaterial3 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MiuixTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(badgeColor),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = if (isUnavailable) RoseIcon.WARNING else RoseIcon.BLUETOOTH,
            modifier = Modifier.size(22.dp),
            tint = iconTint,
        )
    }
}

@Composable
private fun AddDeviceCandidateGroup(
    supported: List<DeviceCandidate>,
    nameCounts: Map<String, Int>,
    selectedDeviceId: DeviceId?,
    connectionPhase: ConnectionPhase?,
    connectionError: ControlError?,
    pending: Boolean,
    onConnect: (DeviceId) -> Unit,
    onCancelConnection: () -> Unit,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    AppGroup(title = "可连接的耳机") {
        supported.forEachIndexed { index, candidate ->
            key(candidate.deviceId) {
                if (!isMaterial3 && index > 0) {
                    MiuixHorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                AddDeviceCandidateRow(
                    candidate = candidate,
                    distinguishName = (nameCounts[candidate.addDeviceName()] ?: 0) > 1,
                    phase = connectionPhase.takeIf { candidate.deviceId == selectedDeviceId },
                    error = connectionError.takeIf { candidate.deviceId == selectedDeviceId },
                    blockedReason = "正在连接其他耳机".takeIf { pending && candidate.deviceId != selectedDeviceId },
                    onConnect = { onConnect(candidate.deviceId) },
                    onCancelConnection = onCancelConnection,
                )
            }
        }
    }
}

@Composable
private fun AddDeviceCandidateRow(
    candidate: DeviceCandidate,
    distinguishName: Boolean,
    phase: ConnectionPhase?,
    error: ControlError?,
    blockedReason: String?,
    onConnect: () -> Unit,
    onCancelConnection: () -> Unit,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val subtitle = candidateMetadata(candidate, distinguishName)
    val rowContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val artworkShape = if (isMaterial3) MaterialTheme.shapes.medium else RoundedCornerShape(12.dp)
                val artworkBg = when {
                    phase != null && isMaterial3 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    isMaterial3 -> MaterialTheme.colorScheme.surfaceContainerLowest
                    else -> MiuixTheme.colorScheme.secondaryContainer
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(artworkShape)
                        .background(artworkBg)
                        .then(
                            if (isMaterial3) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = artworkShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    DeviceArtwork(candidate.profile, Modifier.fillMaxWidth().height(52.dp), compact = true)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AppText(
                        text = candidate.addDeviceName(),
                        role = AppTextRole.Title,
                        maxLines = 1,
                    )
                    if (subtitle != null) {
                        AppText(
                            text = subtitle,
                            role = AppTextRole.Footnote,
                            maxLines = 1,
                        )
                    }
                    blockedReason?.let {
                        AppText(it, role = AppTextRole.Footnote)
                    }
                }
                if (phase != null) {
                    AddDeviceCompactActionButton(
                        text = "取消连接",
                        primary = false,
                        enabled = true,
                        onClick = onCancelConnection,
                    )
                } else {
                    AddDeviceCompactActionButton(
                        text = if (error == null) "连接" else "重试",
                        primary = true,
                        enabled = blockedReason == null,
                        onClick = onConnect,
                    )
                }
            }
            if (phase != null) {
                AddDeviceConnectingStrip(phase)
            } else if (error != null) {
                AddDeviceInlineNotice(
                    title = "连接失败",
                    message = error.message,
                    isError = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (isMaterial3) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ListItemDefaults.shapes().shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            rowContent()
        }
    } else {
        rowContent()
    }
}

@Composable
private fun AddDeviceConnectingStrip(phase: ConnectionPhase) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val containerColor = if (isMaterial3) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(
            text = phase.displayText(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            role = AppTextRole.Summary,
        )
        AppSyncIndicator(active = true)
    }
}

@Composable
private fun AddDeviceInlineNotice(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val containerColor = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        isError -> MiuixTheme.colorScheme.error.copy(alpha = 0.10f)
        isMaterial3 -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MiuixTheme.colorScheme.secondaryContainer
    }
    val textColor = when {
        isError && isMaterial3 -> MaterialTheme.colorScheme.onErrorContainer
        isError -> MiuixTheme.colorScheme.error
        else -> appColor(AppColorRole.Summary)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isError) {
            AppIcon(RoseIcon.ERROR, Modifier.padding(top = 2.dp).size(16.dp), tint = textColor)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let { AppText(it, role = AppTextRole.Body, color = textColor) }
            AppText(
                text = message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                role = AppTextRole.Footnote,
                color = textColor,
            )
        }
    }
}

@Composable
private fun AddDeviceCompactActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTextButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            cornerRadius = 18.dp,
            minWidth = 0.dp,
            minHeight = 36.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            colors = if (primary) {
                MiuixButtonDefaults.textButtonColorsPrimary()
            } else {
                MiuixButtonDefaults.textButtonColors()
            },
            textStyle = MiuixTheme.textStyles.subtitle,
        )

        InterfaceStyle.MATERIAL3 -> {
            val height = ButtonDefaults.ExtraSmallContainerHeight
            val shapes = ButtonDefaults.shapesFor(height)
            val padding = ButtonDefaults.contentPaddingFor(height)
            if (primary) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    shapes = shapes,
                    contentPadding = padding,
                    modifier = Modifier.heightIn(min = height),
                ) { Text(text) }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    enabled = enabled,
                    shapes = shapes,
                    contentPadding = padding,
                    modifier = Modifier.heightIn(min = height),
                ) { Text(text) }
            }
        }
    }
}

private fun DeviceCandidate.addDeviceName(): String = name?.takeIf { it.isNotBlank() } ?: profile?.displayName ?: "ROSE 耳机"

private fun candidateMetadata(candidate: DeviceCandidate, distinguishName: Boolean): String? {
    val parts = buildList {
        candidate.profile?.configurationCaption?.let(::add)
        candidate.rssi?.let { add(signalStrengthLabel(it)) }
        if (distinguishName) add("地址末位 ${candidate.deviceId.value.takeLast(8)}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun signalStrengthLabel(rssi: Int): String = when {
    rssi >= -60 -> "信号强"
    rssi >= -78 -> "信号中"
    else -> "信号弱"
}

private fun searchStatusText(
    count: Int,
    pending: Boolean,
    scanning: Boolean,
    scanTimedOut: Boolean,
    scanError: ControlError?,
): String = when {
    pending -> "正在连接所选耳机"
    scanning -> "正在搜索附近的耳机…"
    scanError != null -> "搜索未完成"
    count == 0 && scanTimedOut -> "搜索已超时，可重新搜索"
    count == 0 -> "暂未找到受支持的耳机"
    else -> "搜索已结束"
}

@Composable
private fun AddDeviceTroubleshooting(
    canScan: Boolean,
    supported: List<DeviceCandidate>,
    unsupported: List<DeviceCandidate>,
    onOpenSystemSettings: (() -> Unit)?,
    onDiagnostics: () -> Unit,
) {
    var troubleshootingExpanded by rememberSaveable { mutableStateOf(false) }
    key("troubleshooting") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppGroup {
                AddDeviceTroubleshootingToggleRow(
                    expanded = troubleshootingExpanded,
                    onToggle = { troubleshootingExpanded = !troubleshootingExpanded },
                )
                if (troubleshootingExpanded) {
                    AddDeviceTroubleshootingActions(onOpenSystemSettings, onDiagnostics)
                }
            }
            if (troubleshootingExpanded && canScan) {
                AddDeviceTroubleshootingDeviceGroups(supported, unsupported)
            }
        }
    }
}

@Composable
private fun AddDeviceTroubleshootingToggleRow(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val toggleTitle = if (expanded) "收起排查指引" else "找不到耳机？"
    val toggleSummary = if (expanded) null else "查看配对步骤、蓝牙设置与其他设备"
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(
            title = toggleTitle,
            summary = toggleSummary,
            startAction = { AppIcon(RoseIcon.INFO) },
            endActions = {
                ExpansionIcon(
                    expanded = expanded,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            onClick = onToggle,
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            onClick = onToggle,
            modifier = Modifier.semantics { role = Role.Button },
            leadingContent = { AppIcon(RoseIcon.INFO) },
            trailingContent = {
                ExpansionIcon(
                    expanded = expanded,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            supportingContent = toggleSummary?.let { { Text(it) } },
            colors = rowColors(),
        ) { Text(toggleTitle) }
    }
}

@Composable
private fun AddDeviceTroubleshootingActions(
    onOpenSystemSettings: (() -> Unit)?,
    onDiagnostics: () -> Unit,
) {
    val isMiuix = LocalInterfaceStyle.current == InterfaceStyle.MIUIX
    if (isMiuix) MiuixHorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    AddDeviceTroubleshootingSteps()
    if (isMiuix) MiuixHorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    onOpenSystemSettings?.let {
        AppArrowRow(
            title = "打开蓝牙设置",
            summary = "检查系统蓝牙开关与已配对设备",
            icon = RoseIcon.BLUETOOTH,
            endIcon = RoseIcon.OPEN_IN_NEW,
            onClick = it,
        )
        if (isMiuix) MiuixHorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
    AppArrowRow(
        title = "查看诊断",
        summary = "查看蓝牙扫描与连接状态记录",
        icon = RoseIcon.LOG,
        onClick = onDiagnostics,
    )
}

@Composable
private fun AddDeviceTroubleshootingDeviceGroups(
    supported: List<DeviceCandidate>,
    unsupported: List<DeviceCandidate>,
) {
    if (supported.isNotEmpty()) {
        AppGroup(title = "设备识别信息") {
            supported.forEach { candidate ->
                val info = listOfNotNull(
                    candidate.profile?.configurationCaption,
                    candidate.rssi?.let { "$it dBm" },
                ).joinToString(" · ")
                AppInfoRow(candidate.addDeviceName(), info.ifEmpty { "已识别" })
            }
        }
    }
    if (unsupported.isNotEmpty()) {
        AppGroup(title = "其他蓝牙设备") {
            unsupported.forEach { candidate ->
                AppInfoRow(
                    label = candidate.name?.takeIf { it.isNotBlank() } ?: "未命名蓝牙设备",
                    value = "暂不支持",
                )
            }
        }
    }
}

@Composable
private fun AddDeviceTroubleshootingSteps() {
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("排查指引", role = AppTextRole.Title)
            AppText(
                text = "1. 打开充电盒，确认耳机已开启且有电。\n2. 按耳机说明进入配对模式。\n3. 将耳机靠近当前设备，然后重新搜索。",
                role = AppTextRole.Summary,
            )
        }
    }
    if (isMaterial3) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ListItemDefaults.shapes().shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            content()
        }
    } else {
        content()
    }
}
