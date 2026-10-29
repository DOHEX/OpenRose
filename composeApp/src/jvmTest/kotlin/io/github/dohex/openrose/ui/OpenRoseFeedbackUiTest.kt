@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.noiseControlCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseFeedbackUiTest {
    @Test
    fun pendingFeedbackDoesNotMoveTheNoiseControls() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 60, sessionId = SESSION_ID, setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("wind"), stage = SettingStage.SUBMITTED,
            )
            val harness = createHarness(state(operation, style).copy(settingOperations = emptyList()))
            try {
                harness.render()
                harness.clickText("更多降噪设置")
                harness.render()
                val before = harness.textBounds("降噪模式")
                harness.state.value = harness.state.value.copy(settingOperations = listOf(operation))
                harness.render()
                assertEquals(before, harness.textBounds("降噪模式"), "feedback must not shift the control being used in $style")
                assertTrue(harness.hasText("正在处理设置，等待设备确认"))
                harness.state.value = harness.state.value.copy(settingOperations = listOf(operation.copy(
                    stage = SettingStage.CONFIRMED, confirmedFields = setOf(DeviceField.NOISE_MODE),
                )))
                harness.render()
                assertTrue(harness.hasText("设备已确认设置"))
                kotlinx.coroutines.delay(3_100)
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "success feedback expires in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun anExistingConfirmationDoesNotReappearOnUnrelatedControlPages() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 61, sessionId = SESSION_ID, setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("anc"), stage = SettingStage.CONFIRMED,
                confirmedFields = setOf(DeviceField.NOISE_MODE),
            )
            val harness = createHarness(state(operation, style))
            try {
                harness.render()
                harness.clickText("更多降噪设置")
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "a historical confirmation is not feedback for this page in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun pendingSettingShowsConciseFeedbackAndItsFullRecordOnlyInDiagnostics() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 42,
                sessionId = SESSION_ID,
                setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("wind"),
                stage = SettingStage.WRITTEN,
            )
            val harness = createHarness(state(operation, style))
            try {
                harness.render()
                assertTrue(harness.hasText("正在处理设置，等待设备确认"), "pending feedback in $style")
                assertFalse(harness.hasText("最近设置操作"), "home must not present an operation-history block in $style")
                assertFalse(harness.hasText("请求 #42：降噪模式"), "request identifiers stay in diagnostics in $style")
                assertFalse(harness.hasText("请求值：抗风噪"), "request values stay in diagnostics in $style")

                harness.clickText("更多降噪设置")
                harness.render()
                assertTrue(harness.hasText("设备已确认：主动降噪"), "pending requests must preserve the confirmed mode in $style")
                assertTrue(harness.hasText("正在处理设置，等待设备确认"), "control page feedback in $style")
                assertFalse(harness.hasText("请求 #42：降噪模式"), "control pages must not show diagnostic records in $style")

                harness.clickTab("系统集成")
                harness.render()
                harness.clickText("运行状态与诊断")
                harness.render()
                assertTrue(harness.hasText("请求 #42：降噪模式"), "diagnostics retain the request identity in $style")
                assertTrue(harness.hasText("请求值：抗风噪"), "diagnostics retain the requested value in $style")
                assertTrue(harness.hasText("底层写入完成 · 等待设备确认"), "diagnostics retain the operation phase in $style")

                val current = harness.state.value
                val device = requireNotNull(current.device)
                val confirmedOperation = operation.copy(
                    stage = SettingStage.CONFIRMED,
                    confirmedFields = setOf(DeviceField.NOISE_MODE),
                )
                harness.state.value = current.copy(
                    device = device.copy(fields = device.fields +
                        (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("wind"), "wind"))),
                    settingOperations = listOf(confirmedOperation),
                )
                harness.clickTab("我的耳机")
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "a completed operation is not replayed when returning home in $style")
                assertTrue(harness.hasText("已确认：抗风噪"), "the current mode comes from device state in $style")
                assertFalse(harness.hasText("请求 #42：降噪模式"), "a confirmed operation is still not history on home in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun failedOperationShowsItsReasonAndOpensTheDiagnosticRecord() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val error = ControlError(ControlErrorCode.TIMEOUT, "没有收到有效的设置回读")
            val operation = SettingOperation(
                id = 43,
                sessionId = SESSION_ID,
                setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("wind"),
                stage = SettingStage.TIMED_OUT,
                error = error,
            )
            val harness = createHarness(state(operation, style, lastError = error))
            try {
                harness.render()
                assertTrue(harness.hasText("等待设备确认超时"), "failure summary in $style")
                assertTrue(harness.hasText(error.message), "failure reason in $style")
                assertTrue(harness.hasText("查看详情"), "failure links to diagnostics in $style")
                assertFalse(harness.hasText("请求 #43：降噪模式"), "request details stay out of home in $style")

                harness.clickText("查看详情")
                harness.render()
                assertTrue(harness.hasText("请求 #43：降噪模式"), "diagnostics retain request details in $style")
                assertTrue(harness.hasText("请求值：抗风噪"), "diagnostics retain the requested value in $style")
                assertTrue(harness.hasText("设置超时 · 未获完整设备确认"), "diagnostics retain the failure stage in $style")
                assertTrue(harness.hasText(error.message), "diagnostics retain the failure reason in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun settingSubmissionWaitsForDeviceConfirmationAndStyleSwitchDoesNotResend() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val confirmed = SettingOperation(
                id = 45,
                sessionId = SESSION_ID,
                setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("anc"),
                stage = SettingStage.CONFIRMED,
                confirmedFields = setOf(DeviceField.NOISE_MODE),
            )
            val harness = createHarness(state(confirmed, style))
            try {
                harness.render()
                harness.clickText("更多降噪设置")
                harness.render()
                harness.clickText("通透")

                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(
                        DeviceSetting.NOISE_MODE to FieldValue.Text("transparency"),
                    ),
                    harness.settingWrites,
                    "the user request crosses the public OpenRoseApp callback once in $style",
                )
                val submitted = confirmed.copy(
                    requestedValue = FieldValue.Text("transparency"),
                    stage = SettingStage.SUBMITTED,
                    confirmedFields = emptySet(),
                )
                harness.state.value = harness.state.value.copy(settingOperations = listOf(submitted))
                harness.render()
                assertTrue(harness.hasText("设备已确认：主动降噪"), "submission keeps the prior confirmed mode in $style")
                assertTrue(harness.hasText("正在处理设置，等待设备确认"), "submission shows a waiting result in $style")
                assertTrue(harness.hasDisabledText("通透"), "pending settings keep the existing disabled-control rule in $style")

                harness.clickTab("设置")
                harness.render()
                harness.clickText("组件风格")
                harness.render()
                val nextStyle = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX
                harness.clickText(if (nextStyle == InterfaceStyle.MIUIX) "Miuix" else "Material 3")
                harness.render()
                assertEquals(nextStyle, harness.state.value.settings.interfaceStyle)
                assertEquals(1, harness.settingWrites.size, "style changes do not resubmit settings in $style")

                harness.clickTab("我的耳机")
                harness.render()
                harness.clickText("更多降噪设置")
                harness.render()
                assertTrue(harness.hasText("设备已确认：主动降噪"), "navigation preserves the confirmed value in $style")
                assertEquals(1, harness.settingWrites.size, "navigation and rendering do not resubmit settings in $style")

                val current = harness.state.value
                val device = requireNotNull(current.device)
                harness.state.value = current.copy(
                    device = device.copy(fields = device.fields +
                        (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("transparency"), "transparency"))),
                    settingOperations = listOf(submitted.copy(
                        stage = SettingStage.CONFIRMED,
                        confirmedFields = setOf(DeviceField.NOISE_MODE),
                    )),
                )
                harness.render()
                assertTrue(harness.hasText("设备已确认：通透"), "the control selection changes after device confirmation in $style")
                assertEquals(1, harness.settingWrites.size, "device confirmation does not issue another setting in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun oldSessionEqConfirmationStaysInDiagnosticsAcrossDisconnectAndReconnect() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 44,
                sessionId = SESSION_ID,
                setting = DeviceSetting.CUSTOM_EQ,
                requestedValue = FieldValue.IntegerList(listOf(0, 1, 2)),
                stage = SettingStage.WRITTEN,
                writtenFrames = 2,
                totalFrames = 2,
                confirmedFields = setOf(DeviceField.EQ),
            )
            val initial = state(operation, style).copy(connection = ConnectionState.Disconnected)
            val harness = createHarness(initial)
            try {
                harness.render()
                assertTrue(harness.hasText("连接你的 ROSE 耳机"), "disconnect returns home without old device controls in $style")
                assertFalse(harness.hasText("请求 #44：自定义 EQ"), "ended session detail stays off home in $style")
                assertFalse(harness.hasText("模式已确认，曲线尚未确认"), "partial EQ confirmation stays in diagnostics in $style")

                val oldDevice = requireNotNull(harness.state.value.device)
                val newSessionId = SESSION_ID + 1
                val newDevice = oldDevice.copy(
                    sessionId = newSessionId,
                    fields = oldDevice.fields + (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("normal"), "normal")),
                )
                harness.state.value = harness.state.value.copy(
                    connection = ConnectionState.Connected(oldDevice.deviceId, newSessionId, oldDevice.profile, ConnectionPhase.READY),
                    device = newDevice,
                )
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "the prior session must not become a new confirmation in $style")
                assertFalse(harness.hasText("请求 #44：自定义 EQ"), "prior requests stay off the new session's home in $style")

                harness.clickTab("系统集成")
                harness.render()
                harness.clickText("运行状态与诊断")
                harness.render()
                assertTrue(harness.hasText("请求 #44：自定义 EQ"), "diagnostics retain the old operation in $style")
                assertTrue(harness.hasText("发送进度：2/2 步"), "diagnostics retain EQ write progress in $style")
                assertTrue(harness.hasText("模式已确认，曲线尚未确认"), "diagnostics retain partial EQ confirmation in $style")
                assertTrue(harness.hasText("已结束会话 $SESSION_ID 的操作记录"), "diagnostics retain the session link in $style")
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(initialState: ControlState): FeedbackHarness {
        val state = mutableStateOf(initialState)
        val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val scene = createOpenRoseUiTestScene(1200, 1400) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings -> state.value = state.value.copy(settings = settings) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { setting, value -> settingWrites += setting to value },
            )
        }
        return FeedbackHarness(scene, state, settingWrites)
    }

    private fun state(
        operation: SettingOperation,
        style: InterfaceStyle,
        lastError: ControlError? = null,
    ): ControlState {
        val profile = ModelProfile.I7_RS_V2
        val deviceId = DeviceId("feedback-test-i7")
        val device = DeviceState(
            deviceId = deviceId,
            sessionId = SESSION_ID,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = setOf(DeviceField.NOISE_MODE),
                writableSettings = setOf(DeviceSetting.NOISE_MODE),
                noiseControl = noiseControlCapabilities(profile),
            ),
            fields = mapOf(DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc")),
        )
        return ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                module = ModuleAvailability.NotEnabled,
            ),
            connection = ConnectionState.Connected(deviceId, SESSION_ID, profile, ConnectionPhase.READY),
            settings = UserSettings(interfaceStyle = style),
            device = device,
            lastError = lastError,
            settingOperations = listOf(operation),
        )
    }

    private class FeedbackHarness(
        scene: androidx.compose.ui.ImageComposeScene,
        val state: MutableState<ControlState>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 38L
    }
}
