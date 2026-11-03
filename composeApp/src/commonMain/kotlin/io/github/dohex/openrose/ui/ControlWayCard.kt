package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.HandoffOutcome
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.OwnerFailureStage
import io.github.dohex.openrose.core.OwnerHolder
import io.github.dohex.openrose.core.OwnerReadiness

private val controlWayOptions = listOf(
    AppOption(ControlWay.ORDINARY, "普通控制", testTag = ControlWayTags.option(ControlWay.ORDINARY)),
    AppOption(ControlWay.MODULE, "模块控制", testTag = ControlWayTags.option(ControlWay.MODULE)),
)

/** The ordinary/module choice exists only where Android hosts the control runtime. */
internal fun ControlState.controlWayApplicable(): Boolean = platform.hostPlatform == HostPlatform.ANDROID

/**
 * Test tags of the control-way card. Tags that report state carry the state's name, so tests
 * assert the state itself and never the wording shown for it.
 */
internal object ControlWayTags {
    /** Every control-way tag starts with this, so a host without the choice can be shown to expose none. */
    const val PREFIX = "control-way."
    const val CARD = "${PREFIX}card"
    const val MODULE_NOTE = "${PREFIX}module-note"
    const val NONE_NOTICE = "${PREFIX}none-notice"
    const val DISABLE_MODULE = "${PREFIX}disable-module"
    const val SWITCH_BLOCKED_NOTICE = "${PREFIX}switch-blocked-notice"
    const val LAST_HANDOFF = "${PREFIX}last-handoff"
    const val HOLDER = "${PREFIX}holder"

    fun option(way: ControlWay) = "${PREFIX}option.${way.name}"
    fun failureStage(stage: OwnerFailureStage) = "${PREFIX}stage.${stage.name}"

    fun readiness(readiness: OwnerReadiness) = "${PREFIX}readiness." + when (readiness) {
        OwnerReadiness.Ready -> "Ready"
        OwnerReadiness.Starting -> "Starting"
        is OwnerReadiness.Idle -> "Idle"
        is OwnerReadiness.Unavailable -> "Unavailable"
        is OwnerReadiness.Switching -> "Switching"
        is OwnerReadiness.SwitchBlocked -> "SwitchBlocked"
    }

    fun connection(connection: ConnectionState) = "${PREFIX}connection." + when (connection) {
        ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected"
    }
}

/**
 * Chooses between 普通控制 and 模块控制 and reports three separate facts: the saved selection,
 * whether its owner is ready, and the earphone 控制连接. Selecting never implies the owner exists.
 */
@Composable
internal fun ControlWayCard(
    state: ControlState,
    onSelectControlWay: (ControlWay) -> Unit,
    onDisableModuleControl: () -> Unit,
) {
    val owner = state.owner
    val readiness = owner.readiness
    val switching = readiness is OwnerReadiness.Switching
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ControlWayTags.CARD),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "控制方式") {
            AppRadioGroup(
                options = controlWayOptions,
                selected = owner.selected.takeIf { it != ControlWay.NONE },
                enabled = !switching,
            ) { way ->
                if (way != owner.selected) onSelectControlWay(way)
            }
            moduleAvailabilityNote(state.platform.module)?.let {
                AppText(it, Modifier.controlWayTextPadding().testTag(ControlWayTags.MODULE_NOTE), role = AppTextRole.Summary)
            }
            if (owner.selected == ControlWay.NONE) {
                AppText(
                    "模块控制已停用，未启用普通控制",
                    Modifier.controlWayTextPadding().testTag(ControlWayTags.NONE_NOTICE),
                    role = AppTextRole.Summary,
                )
            }
            if (owner.selected == ControlWay.MODULE) {
                AppButton(
                    text = "停用模块控制",
                    onClick = onDisableModuleControl,
                    enabled = !switching,
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp).testTag(ControlWayTags.DISABLE_MODULE),
                    kind = AppButtonKind.Secondary,
                )
            }
        }

        AppGroup(title = "控制状态") {
            AppInfoRow("已选择方式", selectedLabel(owner.selected))
            val readinessColor = when (readiness) {
                OwnerReadiness.Ready -> appColor(AppColorRole.Primary)
                is OwnerReadiness.Unavailable, is OwnerReadiness.SwitchBlocked -> appColor(AppColorRole.Error)
                else -> appColor(AppColorRole.Summary)
            }
            Tagged(ControlWayTags.readiness(readiness)) {
                AppInfoRow("所有者状态", readinessLabel(readiness), readinessColor)
            }
            when (readiness) {
                OwnerReadiness.Ready, OwnerReadiness.Starting -> Unit
                is OwnerReadiness.Idle -> AppInfoRow("原因", readiness.reason)
                is OwnerReadiness.Unavailable -> {
                    Tagged(ControlWayTags.failureStage(readiness.stage)) {
                        AppInfoRow("失败阶段", stageLabel(readiness.stage))
                    }
                    AppInfoRow("原因", readiness.reason)
                }
                is OwnerReadiness.Switching -> AppInfoRow("切换目标", wayLabel(readiness.request.to))
                is OwnerReadiness.SwitchBlocked -> {
                    Tagged(ControlWayTags.failureStage(readiness.stage)) {
                        AppInfoRow("失败阶段", stageLabel(readiness.stage))
                    }
                    AppInfoRow("原因", readiness.reason)
                    AppText(
                        "无法确认上一个所有者已释放控制连接，为避免两个所有者同时连接，没有进行接管。",
                        Modifier.controlWayTextPadding().testTag(ControlWayTags.SWITCH_BLOCKED_NOTICE),
                        role = AppTextRole.Summary,
                    )
                }
            }
            Tagged(ControlWayTags.connection(state.connection)) {
                AppInfoRow("耳机控制连接", connectionLabel(state.connection))
            }
        }

        owner.holder?.let { Tagged(ControlWayTags.HOLDER) { OwnerHolderGroup(it) } }

        val handoff = owner.lastHandoff
        if (handoff is HandoffOutcome.Blocked && !handoff.duplicatesReadiness(readiness)) {
            Tagged(ControlWayTags.LAST_HANDOFF) {
                AppGroup(title = "上次切换") {
                    AppInfoRow("切换结果", "未完成：${wayLabel(handoff.request.from)} → ${wayLabel(handoff.request.to)}")
                    Tagged(ControlWayTags.failureStage(handoff.stage)) {
                        AppInfoRow("未完成阶段", stageLabel(handoff.stage))
                    }
                    AppInfoRow("未完成原因", handoff.reason)
                }
            }
        }
    }
}

@Composable
private fun OwnerHolderGroup(holder: OwnerHolder) {
    AppGroup(title = "当前所有者") {
        AppInfoRow("所有者方式", wayLabel(holder.way))
        AppInfoRow("所有者包名", holder.packageName)
        AppInfoRow("所有者进程", holder.processName)
        AppInfoRow("所有者代际", holder.generation.toString())
        holder.uid?.let { AppInfoRow("UID", it.toString()) }
        holder.pid?.let { AppInfoRow("PID", it.toString()) }
    }
}

private fun Modifier.controlWayTextPadding(): Modifier = padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)

private fun HandoffOutcome.Blocked.duplicatesReadiness(readiness: OwnerReadiness): Boolean =
    readiness is OwnerReadiness.SwitchBlocked && readiness.request.id == request.id &&
        readiness.stage == stage && readiness.reason == reason

/** The module cannot take over until it is available, whichever way it is unavailable. */
private fun moduleAvailabilityNote(module: ModuleAvailability): String? =
    module.unreadyClause()?.let { "$it。仍可选择模块控制，但模块可用前无法接管控制。" }

private fun wayLabel(way: ControlWay): String = when (way) {
    ControlWay.ORDINARY -> "普通控制"
    ControlWay.MODULE -> "模块控制"
    ControlWay.NONE -> "未启用"
}

private fun selectedLabel(way: ControlWay): String = when (way) {
    ControlWay.NONE -> "无（模块控制已停用）"
    else -> wayLabel(way)
}

private fun readinessLabel(readiness: OwnerReadiness): String = when (readiness) {
    OwnerReadiness.Ready -> "就绪"
    OwnerReadiness.Starting -> "启动中"
    is OwnerReadiness.Idle -> "空闲"
    is OwnerReadiness.Unavailable -> "不可用"
    is OwnerReadiness.Switching -> "切换中"
    is OwnerReadiness.SwitchBlocked -> "切换未完成"
}

private fun stageLabel(stage: OwnerFailureStage): String = when (stage) {
    OwnerFailureStage.SELECTION_PERSISTENCE -> "保存控制方式"
    OwnerFailureStage.DISCOVERY -> "发现控制端"
    OwnerFailureStage.AUTHORIZATION -> "获取授权"
    OwnerFailureStage.CONNECTION -> "建立通道"
    OwnerFailureStage.RELEASE -> "确认释放"
    OwnerFailureStage.ACTIVATION -> "启动所有者"
    OwnerFailureStage.OWNER_RUNTIME -> "所有者运行"
}

private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> "未连接"
    is ConnectionState.Connecting -> "连接中"
    is ConnectionState.Connected -> buildString {
        append("已连接 · ").append(connection.profile.shortLabel).append(' ').append(connection.profile.configurationLabel)
        if (connection.phase != ConnectionPhase.READY) append("（正在读取状态）")
    }
}
