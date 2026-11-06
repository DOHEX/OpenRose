package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.noiseControlCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseFeedbackUiTest {
    @Test
    fun pendingFeedbackDoesNotMoveTheNoiseControls() {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 60, sessionId = SESSION_ID, setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("wind"), stage = SettingStage.SUBMITTED,
            )
            withHarness(state(operation).copy(settingOperations = persistentListOf()), style, wholeApp = false) { harness ->
                harness.render()
                val before = harness.clickableBounds("通透")
                harness.state.value = harness.state.value.copy(settingOperations = persistentListOf(operation))
                harness.render(frames = 20)
                assertEquals(before, harness.clickableBounds("通透"), "feedback must not shift the control being used in $style")
                assertTrue(harness.hasText("正在处理设置，等待设备确认"))
                harness.state.value = harness.state.value.copy(settingOperations = persistentListOf(operation.copy(
                    stage = SettingStage.CONFIRMED, confirmedFields = persistentSetOf(DeviceField.NOISE_MODE),
                )))
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "confirmed settings must apply silently without bottom banner in $style")
                assertFalse(harness.hasText("正在处理设置，等待设备确认"), "pending feedback disappears once confirmed in $style")
            }
        }
    }

    @Test
    fun anExistingConfirmationDoesNotReappearOnUnrelatedControlPages() {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 61, sessionId = SESSION_ID, setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("anc"), stage = SettingStage.CONFIRMED,
                confirmedFields = persistentSetOf(DeviceField.NOISE_MODE),
            )
            withHarness(state(operation), style) { harness ->
                harness.render()
                harness.clickText("设备详情")
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "a historical confirmation is not feedback for this page in $style")
            }
        }
    }

    @Test
    fun pendingSettingShowsConciseFeedbackAndItsFullRecordOnlyInDiagnostics() {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 42,
                sessionId = SESSION_ID,
                setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("wind"),
                stage = SettingStage.WRITTEN,
            )
            withHarness(state(operation), style) { harness ->
                harness.render()
                assertTrue(harness.hasText("正在处理设置，等待设备确认"), "pending feedback in $style")
                assertFalse(harness.hasText("最近设置操作"), "home must not present an operation-history block in $style")
                assertFalse(harness.hasText("请求 #42：降噪模式"), "request identifiers stay in diagnostics in $style")
                assertFalse(harness.hasText("请求值：抗风噪"), "request values stay in diagnostics in $style")

                harness.clickTab("状态")
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
                    confirmedFields = persistentSetOf(DeviceField.NOISE_MODE),
                )
                harness.state.value = current.copy(
                    device = device.copy(fields = device.fields +
                        (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("wind"), "wind"))),
                    settingOperations = persistentListOf(confirmedOperation),
                )
                harness.clickTab("我的耳机")
                harness.render(frames = 30)
                assertFalse(harness.hasText("设备已确认设置"), "a completed operation is not replayed when returning home in $style")
                assertTrue(harness.hasText("已确认：抗风噪"), "the current mode comes from device state in $style")
                assertFalse(harness.hasText("请求 #42：降噪模式"), "a confirmed operation is still not history on home in $style")
            }
        }
    }

    @Test
    fun failedOperationShowsItsReasonAndOpensTheDiagnosticRecord() {
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
            withHarness(state(operation, lastError = error), style) { harness ->
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
            }
        }
    }

    @Test
    fun abnormalSettingStagesShowErrorCardAndDiagnosticsLink() {
        for (style in InterfaceStyle.entries) {
            val stages = listOf(
                SettingStage.NOT_APPLIED to "设备状态与请求不一致",
                SettingStage.DISCONNECTED to "控制连接已断开，设置未获完整确认",
                SettingStage.FAILED to "设置失败",
            )
            for ((stage, expectedMessage) in stages) {
                val operation = SettingOperation(
                    id = 70,
                    sessionId = SESSION_ID,
                    setting = DeviceSetting.NOISE_MODE,
                    requestedValue = FieldValue.Text("wind"),
                    stage = stage,
                )
                withHarness(state(operation), style, wholeApp = false) { harness ->
                    harness.render()
                    assertTrue(harness.hasText(expectedMessage), "abnormal stage $stage shows message in $style")
                    assertTrue(harness.hasText("查看详情"), "abnormal stage $stage shows diagnostics button in $style")
                    harness.clickText("查看详情")
                    assertEquals(1, harness.diagnosticsRequests.size, "diagnostics requested from $stage in $style")
                }
            }
        }
    }

    @Test
    fun settingSubmissionWaitsForDeviceConfirmationAndStyleSwitchDoesNotResend() {
        for (style in InterfaceStyle.entries) {
            val confirmed = SettingOperation(
                id = 45,
                sessionId = SESSION_ID,
                setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("anc"),
                stage = SettingStage.CONFIRMED,
                confirmedFields = persistentSetOf(DeviceField.NOISE_MODE),
            )
            withHarness(state(confirmed), style) { harness ->
                harness.render()
                harness.clickText("通透")

                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(
                        DeviceSetting.NOISE_MODE to FieldValue.Text("transparency"),
                    ),
                    harness.settingWrites,
                    "the user request reaches the model once in $style",
                )
                val submitted = confirmed.copy(
                    requestedValue = FieldValue.Text("transparency"),
                    stage = SettingStage.SUBMITTED,
                    confirmedFields = persistentSetOf(),
                )
                harness.state.value = harness.state.value.copy(settingOperations = persistentListOf(submitted))
                harness.render()
                assertTrue(harness.hasSelectedText("降噪"), "submission keeps the prior confirmed mode in $style")
                assertTrue(harness.hasText("正在处理设置，等待设备确认"), "submission shows a waiting result in $style")
                assertTrue(harness.hasDisabledText("通透"), "pending settings keep the existing disabled-control rule in $style")

                harness.clickTab("设置")
                harness.render()
                harness.openPersonalization()
                harness.clickText("组件风格")
                harness.render()
                val nextStyle = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX
                harness.clickText(if (nextStyle == InterfaceStyle.MIUIX) "Miuix" else "Material 3 Expressive")
                harness.render()
                harness.clickContentDescription("返回")
                harness.render()
                assertEquals(nextStyle, harness.uiSettings.value.interfaceStyle)
                assertEquals(1, harness.settingWrites.size, "style changes do not resubmit settings in $style")

                harness.clickTab("我的耳机")
                harness.render()
                assertTrue(harness.hasSelectedText("降噪"), "navigation preserves the confirmed value in $style")
                assertEquals(1, harness.settingWrites.size, "navigation and rendering do not resubmit settings in $style")

                val current = harness.state.value
                val device = requireNotNull(current.device)
                harness.state.value = current.copy(
                    device = device.copy(fields = device.fields +
                        (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("transparency"), "transparency"))),
                    settingOperations = persistentListOf(submitted.copy(
                        stage = SettingStage.CONFIRMED,
                        confirmedFields = persistentSetOf(DeviceField.NOISE_MODE),
                    )),
                )
                harness.render()
                assertTrue(harness.hasSelectedText("通透"), "the control selection changes after device confirmation in $style")
                assertFalse(harness.hasText("设备已确认设置"), "confirmed settings apply silently without bottom banner in $style")
                assertEquals(1, harness.settingWrites.size, "device confirmation does not issue another setting in $style")
            }
        }
    }

    @Test
    fun oldSessionEqConfirmationStaysInDiagnosticsAcrossDisconnectAndReconnect() {
        for (style in InterfaceStyle.entries) {
            val operation = SettingOperation(
                id = 44,
                sessionId = SESSION_ID,
                setting = DeviceSetting.CUSTOM_EQ,
                requestedValue = FieldValue.IntegerList(listOf(0, 1, 2)),
                stage = SettingStage.WRITTEN,
                writtenFrames = 2,
                totalFrames = 2,
                confirmedFields = persistentSetOf(DeviceField.EQ),
            )
            val initial = state(operation).copy(connection = ConnectionState.Disconnected)
            withHarness(initial, style) { harness ->
                harness.render()
                assertTrue(harness.hasText("添加你的 ROSE 耳机"), "disconnect returns home without old device controls in $style")
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

                harness.clickTab("状态")
                harness.render()
                harness.clickText("运行状态与诊断")
                harness.render()
                assertTrue(harness.hasText("请求 #44：自定义 EQ"), "diagnostics retain the old operation in $style")
                assertTrue(harness.hasText("发送进度：2/2 步"), "diagnostics retain EQ write progress in $style")
                assertTrue(harness.hasText("模式已确认，曲线尚未确认"), "diagnostics retain partial EQ confirmation in $style")
                assertTrue(harness.hasText("已结束会话 $SESSION_ID 的操作记录"), "diagnostics retain the session link in $style")
            }
        }
    }

    private fun withHarness(initialState: ControlState, style: InterfaceStyle, wholeApp: Boolean = true, block: suspend (FeedbackHarness) -> Unit) = runOpenRoseUiTest(1200, 1400) {
        val state = mutableStateOf(initialState)
        val uiSettings = mutableStateOf(UiSettings(interfaceStyle = style))
        val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val diagnosticsRequests = mutableListOf<Unit>()
        val model = FakeOpenRoseModel(state, uiSettings).apply {
            onSet = { setting, value -> settingWrites += setting to value }
        }
        setContent {
            if (wholeApp) {
                OpenRoseApp(model, BasicPlatformUi("Test"))
            } else {
                ProvideInterfaceStyle(uiSettings.value.interfaceStyle, dark = false) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        HeadphonesPage(model, BasicPlatformUi("Test"), {})
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
                            SettingFeedback(state.value, state.value.activeDevice()) { diagnosticsRequests += Unit }
                        }
                    }
                }
            }
        }
        block(FeedbackHarness(this, state, uiSettings, settingWrites, diagnosticsRequests))
    }

    private fun state(
        operation: SettingOperation,
        lastError: ControlError? = null,
    ): ControlState {
        val profile = ModelProfile.I7_RS_V2
        val deviceId = DeviceId("feedback-test-i7")
        val device = DeviceState(
            deviceId = deviceId,
            sessionId = SESSION_ID,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = persistentSetOf(DeviceField.NOISE_MODE),
                writableSettings = persistentSetOf(DeviceSetting.NOISE_MODE),
                noiseControl = noiseControlCapabilities(profile),
            ),
            fields = persistentMapOf(DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc")),
        )
        return ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
            ),
            connection = ConnectionState.Connected(deviceId, SESSION_ID, profile, ConnectionPhase.READY),
            device = device,
            lastError = lastError,
            settingOperations = persistentListOf(operation),
        )
    }

    private class FeedbackHarness(
        scene: OpenRoseUiTestScene,
        val state: MutableState<ControlState>,
        val uiSettings: MutableState<UiSettings>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
        val diagnosticsRequests: List<Unit>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 38L
    }
}
