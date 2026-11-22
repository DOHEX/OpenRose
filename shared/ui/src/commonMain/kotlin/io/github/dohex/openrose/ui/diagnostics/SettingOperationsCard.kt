package io.github.dohex.openrose.ui.diagnostics

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.ui.AppGroup
import io.github.dohex.openrose.ui.AppInfoRow
import io.github.dohex.openrose.ui.displayFieldValue
import io.github.dohex.openrose.ui.displayName
import io.github.dohex.openrose.ui.statusText

@Composable
internal fun SettingOperationsCard(state: ControlState) {
    val operations = state.settingOperations.takeLast(4).asReversed()
    if (operations.isEmpty()) return
    val sessionId = when (val connection = state.connection) {
        is ConnectionState.Connecting -> connection.sessionId
        is ConnectionState.Connected -> connection.sessionId
        ConnectionState.Disconnected -> null
    }
    AppGroup(title = "设置操作") {
        operations.forEach { operation ->
            val device = state.device?.takeIf { it.sessionId == operation.sessionId && it.sessionId == sessionId }
            AppInfoRow(
                label = "${operation.setting.displayName()} · #${operation.id}",
                value = operation.statusText(),
                summary = operation.summary(device, sessionId),
            )
        }
    }
}

private fun SettingOperation.summary(device: DeviceState?, currentSession: Long?): String = buildList {
    add("请求值：${requestedValueText(device)}")
    if (setting == DeviceSetting.CUSTOM_EQ) {
        if (totalFrames > 0) add("发送进度：$writtenFrames/$totalFrames 步")
        val mode = DeviceField.EQ in confirmedFields
        val curve = DeviceField.CUSTOM_EQ in confirmedFields
        add(
            when {
                mode && curve -> "模式与曲线均已确认"
                mode -> "模式已确认，曲线尚未确认"
                curve -> "曲线已确认，模式尚未确认"
                else -> "模式与曲线尚未确认"
            },
        )
    }
    error?.takeIf { it.message != statusText() }?.let { add(it.message) }
    if (sessionId != currentSession) add("已结束会话 $sessionId 的操作记录")
}.joinToString("\n")

private fun SettingOperation.requestedValueText(device: DeviceState?): String {
    val field = when (setting) {
        DeviceSetting.EQ -> DeviceField.EQ
        DeviceSetting.GAIN -> DeviceField.GAIN
        DeviceSetting.PROMPT_VOLUME -> DeviceField.PROMPT_VOLUME
        DeviceSetting.HEARING_PROTECTION -> DeviceField.HEARING_PROTECTION
        else -> null
    }
    val touch = when (setting) {
        DeviceSetting.TAP_BINDING -> device?.capabilities?.tapControl
        DeviceSetting.TOUCH_BINDING -> device?.capabilities?.touchControl
        else -> null
    }
    return displayFieldValue(field, requestedValue, device?.profile, touch)
}
