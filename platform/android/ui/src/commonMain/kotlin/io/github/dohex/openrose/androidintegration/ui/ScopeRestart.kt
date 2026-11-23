package io.github.dohex.openrose.androidintegration.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartPhase
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartResult
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartStatus
import io.github.dohex.openrose.androidintegration.ui.resources.Res
import io.github.dohex.openrose.androidintegration.ui.resources.ic_restart_scope
import io.github.dohex.openrose.ui.*
import org.jetbrains.compose.resources.vectorResource
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SCOPE_RESTART_NOTICE = "蓝牙可能短暂断开"
private const val SCOPE_RESTART_DONE = "已完成"

private enum class ScopeRestartStage { SELECTING, RUNNING, DONE }

@Composable
internal fun ScopeRestartAction(
    open: Boolean,
    running: Boolean,
    availableTargets: List<String>,
    selected: Set<String>,
    targets: List<String>,
    phase: ScopeRestartPhase?,
    results: List<ScopeRestartResult>?,
    onOpen: () -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val restartIcon = vectorResource(Res.drawable.ic_restart_scope)
    val stage = when {
        running -> ScopeRestartStage.RUNNING
        results != null -> ScopeRestartStage.DONE
        else -> ScopeRestartStage.SELECTING
    }
    val summary = when (stage) {
        ScopeRestartStage.SELECTING -> SCOPE_RESTART_NOTICE
        ScopeRestartStage.RUNNING -> phaseText(phase ?: ScopeRestartPhase.Authorizing)
        ScopeRestartStage.DONE -> SCOPE_RESTART_DONE
    }
    val selecting = stage == ScopeRestartStage.SELECTING
    val onDismiss = { if (stage != ScopeRestartStage.RUNNING) onClose() }
    val rows = @Composable {
        ScopeRestartRows(availableTargets, selected, targets, phase, results, stage, onToggle)
    }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            top.yukonga.miuix.kmp.basic.IconButton(onClick = onOpen, enabled = !running) {
                top.yukonga.miuix.kmp.basic.Icon(restartIcon, contentDescription = "重启作用域")
            }
            OverlayDialog(show = open, title = "重启作用域", onDismissRequest = onDismiss) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = summary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    textAlign = TextAlign.Center,
                    color = DialogDefaults.summaryColor(),
                    maxLines = 1,
                )
                rows()
                Spacer(Modifier.height(12.dp))
                if (stage == ScopeRestartStage.DONE) {
                    AppButton("关闭", onClose, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        AppButton("取消", onClose, enabled = selecting, modifier = Modifier.weight(1f), kind = AppButtonKind.Secondary)
                        Spacer(Modifier.width(20.dp))
                        AppButton("重启", onConfirm, enabled = selecting && selected.isNotEmpty(), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        InterfaceStyle.MATERIAL3 -> {
            androidx.compose.material3.IconButton(onClick = onOpen, enabled = !running) {
                androidx.compose.material3.Icon(restartIcon, contentDescription = "重启作用域")
            }
            if (open) {
                MaterialRestartDialog(stage, summary, selected.isNotEmpty(), onDismiss, onClose, onConfirm, rows)
            }
        }
    }
}

private fun phaseText(phase: ScopeRestartPhase): String = when (phase) {
    ScopeRestartPhase.Authorizing -> "正在请求 Root 授权…"
    ScopeRestartPhase.Signalling -> "正在终止进程…"
    is ScopeRestartPhase.Waiting -> "等待新进程出现，还剩 ${phase.secondsLeft} 秒"
}

private enum class ScopeRestartMark {
    CHECKBOX,
    SKIPPED,
    WAITING,
    WORKING,
    RESTARTED,
    TERMINATED,
    NOT_RUNNING,
    FAILED,
    ;

    companion object {
        fun of(status: ScopeRestartStatus) = when (status) {
            ScopeRestartStatus.RESTARTED -> RESTARTED
            ScopeRestartStatus.TERMINATED -> TERMINATED
            ScopeRestartStatus.NOT_RUNNING -> NOT_RUNNING
            ScopeRestartStatus.FAILED -> FAILED
        }
    }
}

@Composable
private fun ScopeRestartRow(target: String, mark: ScopeRestartMark, checked: Boolean, detail: String?, onToggle: (String) -> Unit) {
    val selecting = mark == ScopeRestartMark.CHECKBOX
    val skipped = mark == ScopeRestartMark.SKIPPED
    val failure = detail?.takeIf { mark == ScopeRestartMark.FAILED }
    val row = Modifier.semantics(mergeDescendants = true) {}
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(
            modifier = row,
            title = target,
            startAction = { ScopeRestartMarkSlot(mark, checked, 26.dp, Modifier.padding(end = 5.dp)) },
            endActions = if (skipped) ({ AppText("未选择", role = AppTextRole.Summary) }) else null,
            summary = failure,
            summaryColor = BasicComponentDefaults.summaryColor(color = appColor(AppColorRole.Error)),
            onClick = if (selecting) ({ onToggle(target) }) else null,
            role = if (selecting) Role.Checkbox else null,
            enabled = !skipped,
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            headlineContent = { Text(target) },
            supportingContent = failure?.let {
                { AppText(it, role = AppTextRole.Footnote, color = appColor(AppColorRole.Error)) }
            },
            leadingContent = { ScopeRestartMarkSlot(mark, checked, 24.dp) },
            trailingContent = if (skipped) ({ Text("未选择") }) else null,
            modifier = row
                .then(if (selecting) Modifier.toggleable(checked, role = Role.Checkbox) { onToggle(target) } else Modifier)
                .then(if (skipped) Modifier.alpha(0.38f) else Modifier),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun ScopeRestartMarkSlot(mark: ScopeRestartMark, checked: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val fade = when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> tween<Float>(150)
            InterfaceStyle.MATERIAL3 -> MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        }
        Crossfade(mark, animationSpec = fade) { shown ->
            when (shown) {
                ScopeRestartMark.CHECKBOX -> when (LocalInterfaceStyle.current) {
                    InterfaceStyle.MIUIX -> top.yukonga.miuix.kmp.basic.Checkbox(state = ToggleableState(checked), onClick = null)
                    InterfaceStyle.MATERIAL3 -> Checkbox(checked, onCheckedChange = null)
                }

                ScopeRestartMark.SKIPPED -> Unit

                ScopeRestartMark.WORKING ->
                    Box(Modifier.size(20.dp).semantics { contentDescription = "正在重启" }) { AppBusyIndicator(size = 20.dp) }

                else -> {
                    val (icon, label, role) = when (shown) {
                        ScopeRestartMark.RESTARTED -> Triple(RoseIcon.CHECK, "已重启", AppColorRole.Primary)
                        ScopeRestartMark.TERMINATED -> Triple(RoseIcon.INFO, "已终止，等待系统拉起", AppColorRole.Summary)
                        ScopeRestartMark.NOT_RUNNING -> Triple(RoseIcon.INFO, "未运行，无需重启", AppColorRole.Summary)
                        ScopeRestartMark.FAILED -> Triple(RoseIcon.CLOSE, "失败", AppColorRole.Error)
                        else -> Triple(RoseIcon.CLOCK, "等待中", AppColorRole.Summary)
                    }
                    AppIcon(icon, modifier = Modifier.size(20.dp), contentDescription = label, tint = appColor(role))
                }
            }
        }
    }
}

@Composable
private fun ScopeRestartRows(
    availableTargets: List<String>,
    selected: Set<String>,
    targets: List<String>,
    phase: ScopeRestartPhase?,
    results: List<ScopeRestartResult>?,
    stage: ScopeRestartStage,
    onToggle: (String) -> Unit,
) {
    val byTarget = results.orEmpty().associateBy { it.target }
    Column {
        availableTargets.forEach { target ->
            val mark = when {
                stage == ScopeRestartStage.SELECTING -> ScopeRestartMark.CHECKBOX

                target !in targets -> ScopeRestartMark.SKIPPED

                else -> byTarget[target]?.status?.let(ScopeRestartMark::of)
                    ?: if (phase == null || phase == ScopeRestartPhase.Authorizing) {
                        ScopeRestartMark.WAITING
                    } else {
                        ScopeRestartMark.WORKING
                    }
            }
            val detail = byTarget[target]?.detail?.takeIf { stage == ScopeRestartStage.DONE }
            ScopeRestartRow(target, mark, target in selected, detail, onToggle)
        }
    }
}

@Composable
private fun MaterialRestartDialog(
    stage: ScopeRestartStage,
    summary: String,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    rows: @Composable () -> Unit,
) {
    val selecting = stage == ScopeRestartStage.SELECTING
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重启作用域") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(summary, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                rows()
            }
        },
        dismissButton = if (stage == ScopeRestartStage.DONE) {
            null
        } else {
            (
                {
                    TextButton(onClick = onClose, enabled = selecting) { Text("取消") }
                }
                )
        },
        confirmButton = {
            if (stage == ScopeRestartStage.DONE) {
                TextButton(onClick = onClose) { Text("关闭") }
            } else {
                TextButton(onClick = onConfirm, enabled = selecting && hasSelection) { Text("重启") }
            }
        },
    )
}
