package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.SettingStage
import kotlinx.coroutines.delay

/** Shows the current session's outcome without exposing the operation record kept in diagnostics. */
@Composable
internal fun SettingFeedback(
    state: ControlState,
    device: DeviceState?,
    onViewDetails: () -> Unit,
) {
    val operation = device?.let { activeDevice ->
        state.settingOperations.lastOrNull { it.sessionId == activeDevice.sessionId }
    }
    val operationError = operation?.error
    val otherError = state.lastError?.takeIf { it != operationError }
    var observedPendingId by remember(device?.sessionId) { mutableStateOf<Long?>(null) }
    var showConfirmation by remember(device?.sessionId) { mutableStateOf(false) }
    LaunchedEffect(operation?.id, operation?.stage) {
        showConfirmation = false
        when {
            operation == null -> observedPendingId = null
            operation.stage in settingPendingStages -> observedPendingId = operation.id
            operation.stage == SettingStage.CONFIRMED && observedPendingId == operation.id -> {
                showConfirmation = true
                delay(3_000)
                showConfirmation = false
                observedPendingId = null
            }
        }
    }
    val visibleOperation = operation?.takeIf {
        it.stage in settingPendingStages || it.stage in settingFailureStages || operationError != null || showConfirmation
    }
    if (visibleOperation == null && otherError == null) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visibleOperation?.let {
            val failure = it.stage in settingFailureStages || operationError != null
            AppDashboardCard {
                AppText(it.setting.displayName(), role = AppTextRole.Footnote)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (it.stage in settingPendingStages) AppBusyIndicator()
                    AppText(
                        it.stage.feedbackMessage(),
                        color = when {
                            failure -> appColor(AppColorRole.Error)
                            it.stage == SettingStage.CONFIRMED -> appColor(AppColorRole.Primary)
                            else -> null
                        },
                    )
                }
                operationError?.let { error ->
                    AppText(error.message, role = AppTextRole.Summary, color = appColor(AppColorRole.Error))
                }
                if (failure) {
                    AppButton("查看详情", onViewDetails, kind = AppButtonKind.Secondary)
                }
            }
        }

        otherError?.let { error ->
            AppDashboardCard {
                AppText(error.message, color = appColor(AppColorRole.Error))
                AppButton("查看详情", onViewDetails, kind = AppButtonKind.Secondary)
            }
        }
    }
}

private val settingPendingStages = setOf(
    SettingStage.SUBMITTED,
    SettingStage.PARTIALLY_WRITTEN,
    SettingStage.WRITTEN,
)

private val settingFailureStages = setOf(
    SettingStage.NOT_APPLIED,
    SettingStage.TIMED_OUT,
    SettingStage.DISCONNECTED,
    SettingStage.FAILED,
)

private fun SettingStage.feedbackMessage(): String = when (this) {
    SettingStage.SUBMITTED, SettingStage.PARTIALLY_WRITTEN, SettingStage.WRITTEN ->
        "正在处理设置，等待设备确认"
    SettingStage.CONFIRMED -> "设备已确认设置"
    SettingStage.NOT_APPLIED -> "设备状态与请求不一致"
    SettingStage.TIMED_OUT -> "等待设备确认超时"
    SettingStage.DISCONNECTED -> "控制连接已断开，设置未获完整确认"
    SettingStage.FAILED -> "设置失败"
}
