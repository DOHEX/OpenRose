package io.github.dohex.openrose.androidintegration.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HandoffOutcome
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.OwnerFailureStage
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.OwnerReadiness
import io.github.dohex.openrose.androidintegration.OwnerState
import io.github.dohex.openrose.ui.*

private val controlWayOptions = listOf(
    AppOption(ControlWay.ORDINARY, "普通控制", testTag = ControlWayTags.option(ControlWay.ORDINARY)),
    AppOption(ControlWay.MODULE, "模块控制", testTag = ControlWayTags.option(ControlWay.MODULE)),
)

/**
 * Test tags of the control-way card. Tags that report state carry the state's name, so tests
 * assert the state itself and never the wording shown for it.
 */
internal object ControlWayTags {
    /** Every control-way tag starts with this, so a host without the choice can be shown to expose none. */
    const val PREFIX = "control-way."
    const val CARD = "${PREFIX}card"
    const val SWITCH_FEEDBACK = "${PREFIX}switch-feedback"
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
}

/**
 * Settings side of the control way: the saved choice only. Whether its owner is ready is reported by
 * [ControlStatusRows] and [ownerVerdict]; selecting never implies the owner exists.
 */
@Composable
internal fun ControlWayGroup(
    integration: IntegrationState,
    onSelectControlWay: (ControlWay) -> Unit,
) {
    val owner = integration.owner
    val readiness = owner.readiness
    val switching = readiness is OwnerReadiness.Switching
    Column(Modifier.fillMaxWidth().testTag(ControlWayTags.CARD)) {
        AppGroup(title = "控制方式") {
            AppRadioGroup(
                options = controlWayOptions,
                selected = owner.selected,
                enabled = !switching,
            ) { way ->
                if (way != owner.selected) onSelectControlWay(way)
            }
            switchFeedback(readiness)?.let {
                AppText(it, Modifier.controlWayTextPadding().testTag(ControlWayTags.SWITCH_FEEDBACK), role = AppTextRole.Summary)
            }
        }
    }
}

/** One line for a handoff in flight or stuck; stage and reason are shown on the status page only. */
private fun switchFeedback(readiness: OwnerReadiness): String? = when (readiness) {
    is OwnerReadiness.Switching -> "正在切换到${wayLabel(readiness.request.to)}…"
    is OwnerReadiness.SwitchBlocked -> "切换未完成，详情见状态页。"
    else -> null
}

/**
 * Status side of the control way, as rows of the host's status card: the saved selection and whether its owner is
 * ready. Why it is not ready is the status hero's, through [ownerVerdict].
 */
@Composable
internal fun ControlStatusRows(owner: OwnerState) {
    val readiness = owner.readiness
    AppFactRow(RoseIcon.SWAP, "控制方式", wayLabel(owner.selected))
    val readinessTone = when (readiness) {
        OwnerReadiness.Ready -> StatusTone.GOOD
        is OwnerReadiness.Unavailable, is OwnerReadiness.SwitchBlocked -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }
    Tagged(ControlWayTags.readiness(readiness)) {
        AppFactRow(RoseIcon.SHIELD, "所有者状态", readinessLabel(readiness), readinessTone)
    }
    when (readiness) {
        is OwnerReadiness.Unavailable -> Tagged(ControlWayTags.failureStage(readiness.stage)) {
            AppFactRow(RoseIcon.ERROR, "失败阶段", stageLabel(readiness.stage), StatusTone.ERROR)
        }

        is OwnerReadiness.SwitchBlocked -> {
            Tagged(ControlWayTags.failureStage(readiness.stage)) {
                AppFactRow(RoseIcon.ERROR, "失败阶段", stageLabel(readiness.stage), StatusTone.ERROR)
            }
            AppText(
                "无法确认上一个所有者已释放控制连接，为避免两个所有者同时连接，没有进行接管。",
                Modifier.controlWayTextPadding().testTag(ControlWayTags.SWITCH_BLOCKED_NOTICE),
                role = AppTextRole.Summary,
            )
        }

        else -> Unit
    }
}

/**
 * The status hero's verdict while the selected owner cannot act, with the reason it gives; null once it is ready. An
 * unavailable module owner adds what [module] says to do about it, so the hero is the one place the failure is told.
 */
internal fun ownerVerdict(owner: OwnerState, module: ModuleAvailability): StatusVerdict? = when (val readiness = owner.readiness) {
    OwnerReadiness.Ready -> null

    OwnerReadiness.Starting -> StatusVerdict(AppStatusKind.Caution, "正在启动", "${wayLabel(owner.selected)}正在启动…")

    is OwnerReadiness.Idle -> StatusVerdict(AppStatusKind.Caution, "控制未运行", readiness.reason)

    is OwnerReadiness.Unavailable -> StatusVerdict(
        AppStatusKind.Error,
        "控制不可用",
        listOfNotNull(readiness.reason, module.nextStep().takeIf { owner.selected == ControlWay.MODULE }).joinToString("\n"),
    )

    is OwnerReadiness.Switching -> StatusVerdict(AppStatusKind.Caution, "正在切换", "正在切换到${wayLabel(readiness.request.to)}…")

    is OwnerReadiness.SwitchBlocked -> StatusVerdict(AppStatusKind.Error, "切换未完成", readiness.reason)
}

/** Developer-grade owner facts for the diagnostics page: who holds the connection and the last blocked handoff. */
@Composable
internal fun ControlOwnerDiagnostics(integration: IntegrationState) {
    val owner = integration.owner
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        owner.holder?.let { Tagged(ControlWayTags.HOLDER) { OwnerHolderGroup(it) } }

        val handoff = owner.lastHandoff
        if (handoff is HandoffOutcome.Blocked && !handoff.duplicatesReadiness(owner.readiness)) {
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

private fun HandoffOutcome.Blocked.duplicatesReadiness(readiness: OwnerReadiness): Boolean = readiness is OwnerReadiness.SwitchBlocked &&
    readiness.request.id == request.id &&
    readiness.stage == stage &&
    readiness.reason == reason

internal fun wayLabel(way: ControlWay): String = when (way) {
    ControlWay.ORDINARY -> "普通控制"
    ControlWay.MODULE -> "模块控制"
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
