package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage

/**
 * Shows the current session's outcome without exposing the operation record kept in diagnostics.
 * [showPending] keeps the progress card for settings that the page does not already show in place.
 */
@Composable
internal fun SettingFeedback(
    state: ControlState,
    device: DeviceState?,
    showPending: (DeviceSetting) -> Boolean = { true },
    modifier: Modifier = Modifier,
    onViewDetails: () -> Unit,
) {
    val operation = device?.let { activeDevice ->
        state.settingOperations.lastOrNull { it.sessionId == activeDevice.sessionId }
    }
    val operationError = operation?.error
    val otherError = state.lastError?.takeIf { it != operationError }
    val visibleOperation = operation?.takeIf { it.needsFeedback(showPending(it.setting)) }
    if (visibleOperation == null && otherError == null) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visibleOperation?.let {
            val failure = it.stage in settingFailureStages || operationError != null
            val headline = it.statusText()
            AppDashboardCard {
                AppText(it.setting.displayName(), role = AppTextRole.Footnote)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (it.stage in settingPendingStages) AppBusyIndicator()
                    AppText(
                        headline,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (failure) appColor(AppColorRole.Error) else null,
                    )
                }
                operationError?.takeIf { error -> error.message != headline }?.let { error ->
                    AppText(
                        error.message,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        role = AppTextRole.Summary,
                        color = appColor(AppColorRole.Error),
                    )
                }
                if (failure) {
                    AppButton("查看诊断", onViewDetails, kind = AppButtonKind.Secondary)
                }
            }
        }

        otherError?.let { error ->
            AppDashboardCard {
                AppText(error.message, color = appColor(AppColorRole.Error))
                AppButton("查看诊断", onViewDetails, kind = AppButtonKind.Secondary)
            }
        }
    }
}

internal fun ControlState.pendingSetting(
    device: DeviceState,
    setting: DeviceSetting? = null,
): SettingOperation? = settingOperations.lastOrNull {
    it.sessionId == device.sessionId && it.stage in settingPendingStages && (setting == null || it.setting == setting)
}

/**
 * A setting accepts a new write once its session is usable and nothing in its group is still pending.
 * Settings in one group change each other's meaning (a level belongs to a noise mode, a binding to its
 * switch), so they wait for each other; unrelated settings stay writable and queue in order in the core.
 * The noise mode itself is usable as soon as the noise-mode session is, which is earlier than a ready
 * session; its levels and cycle still wait for the ready session.
 */
internal fun ControlState.canWrite(device: DeviceState, setting: DeviceSetting): Boolean {
    val group = setting.writeGroup()
    val usable = if (setting == DeviceSetting.NOISE_MODE) canControlNoiseMode(device) else isReady(device)
    return usable && settingOperations.none {
        it.sessionId == device.sessionId && it.stage in settingPendingStages && it.setting.writeGroup() == group
    }
}

private fun DeviceSetting.writeGroup(): DeviceSetting = when (this) {
    DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL, DeviceSetting.NOISE_CYCLE -> DeviceSetting.NOISE_MODE
    DeviceSetting.CUSTOM_EQ -> DeviceSetting.EQ
    DeviceSetting.TOUCH_BINDING -> DeviceSetting.TOUCH_ENABLED
    DeviceSetting.TAP_BINDING -> DeviceSetting.TAP_ENABLED
    DeviceSetting.SPATIAL_MODE, DeviceSetting.SPATIAL_CONTENT -> DeviceSetting.SPATIAL_AUDIO
    DeviceSetting.INDEPENDENT_MIC_MODE -> DeviceSetting.INDEPENDENT_MIC_ACTIVATION
    else -> this
}

internal fun ControlState.noiseModeFeedback(device: DeviceState): String? {
    val last = settingOperations.lastOrNull { it.sessionId == device.sessionId } ?: return null
    if (last.setting != DeviceSetting.NOISE_MODE || !last.needsFeedback(showPending = true)) return null
    return last.error?.message ?: last.stage.label()
}

/** Failures always show; an operation still in progress shows only where the page has no progress of its own. */
private fun SettingOperation.needsFeedback(showPending: Boolean): Boolean {
    val pendingShown = showPending && stage in settingPendingStages
    return pendingShown || stage in settingFailureStages || error != null
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

/**
 * The one wording for a stage, shared by the feedback card and the diagnostics operation list. The three pending
 * stages read alike: how far the transport got is not something a user acts on.
 */
internal fun SettingStage.label(): String = when (this) {
    SettingStage.SUBMITTED, SettingStage.PARTIALLY_WRITTEN, SettingStage.WRITTEN ->
        "正在处理设置，等待设备确认"

    SettingStage.CONFIRMED -> "设备已确认设置"

    SettingStage.NOT_APPLIED -> "设备状态与请求不一致"

    SettingStage.TIMED_OUT -> "等待设备确认超时"

    SettingStage.DISCONNECTED -> "控制连接已断开，设置未获完整确认"

    SettingStage.FAILED -> "设置失败"
}

/** What an operation's row leads with: the device's error when it confirmed yet failed, else its stage's wording. */
internal fun SettingOperation.statusText(): String {
    val failure = error
    return if (stage == SettingStage.CONFIRMED && failure != null) failure.message else stage.label()
}

private val settingNames = mapOf(
    DeviceSetting.NOISE_MODE to "降噪模式",
    DeviceSetting.ANC_LEVEL to "降噪档位",
    DeviceSetting.TRANSPARENCY_LEVEL to "通透档位",
    DeviceSetting.NOISE_CYCLE to "降噪轮换",
    DeviceSetting.EQ to "预设 EQ",
    DeviceSetting.GAME_MODE to "游戏模式",
    DeviceSetting.TAP_ENABLED to "轻拍开关",
    DeviceSetting.TAP_BINDING to "轻拍绑定",
    DeviceSetting.TOUCH_BINDING to "触控绑定",
    DeviceSetting.TOUCH_ENABLED to "触控总开关",
    DeviceSetting.EAR_DETECTION to "佩戴检测功能",
    DeviceSetting.AUDIO_MODE to "音频模式",
    DeviceSetting.MULTIPOINT to "多点连接",
    DeviceSetting.SPATIAL_MODE to "空间模式",
    DeviceSetting.SPATIAL_CONTENT to "空间音频内容",
    DeviceSetting.EAR_ADAPTIVE to "耳道自适应",
    DeviceSetting.INDEPENDENT_MIC_ACTIVATION to "独立麦克风激活",
    DeviceSetting.INDEPENDENT_MIC_MODE to "独立麦克风模式",
    DeviceSetting.SPATIAL_AUDIO to "空间音频",
    DeviceSetting.PROMPT_VOLUME to "提示音音量",
    DeviceSetting.GAIN to "增益档位",
    DeviceSetting.HEARING_PROTECTION to "听力保护阈值",
    DeviceSetting.BASS_BOOST to "低音增强",
    DeviceSetting.PROMPT_LANGUAGE to "提示音语言",
    DeviceSetting.CUSTOM_EQ to "自定义 EQ",
)

internal fun DeviceSetting.displayName(): String = settingNames.getValue(this)
