package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.BridgeIpcState
import io.github.dohex.openrose.androidintegration.BridgeSessionState
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.SystemBridgeDiagnostics
import io.github.dohex.openrose.androidintegration.SystemEndpointDiagnostics
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidDiagnosticsUiTest {
    @Test
    fun bridgeAndPresentationFactsAreShownInBothStyles() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 2600) {
                val bridge = SystemBridgeDiagnostics(
                    frameworkName = "Test framework",
                    frameworkVersion = "2.0",
                    ipc = BridgeIpcState.AUTHENTICATED,
                    session = BridgeSessionState.STALE,
                    endpoints = mapOf(
                        "fusion" to SystemEndpointDiagnostics(
                            targetPackage = "com.milink.service",
                            targetProcess = "fusion-process",
                            componentVersion = "1.2",
                            entryMatch = "matched entry",
                            ipc = BridgeIpcState.WAITING,
                            features = mapOf("FUSION_CENTER" to "CONFIRMED · device confirmed"),
                        ),
                    ),
                    presentation = mapOf("STATUS_NOTIFICATION" to "ACTION_FAILED · control_receiver_unavailable"),
                )
                val integration = MutableStateFlow(
                    IntegrationState(
                        platform = IntegrationPlatformState(
                            module = ModuleAvailability.Available,
                            systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                            bridge = bridge,
                        ),
                    ),
                )
                val page = mutableStateOf("诊断")
                val model = FakeOpenRoseModel(mutableStateOf(disconnectedAndroidState()), mutableStateOf(UiSettings(interfaceStyle = style)))
                setContent { AndroidTestPage(model, androidTestPlatform(integration), page) }
                val harness = AndroidPageHarness(this, page)
                harness.render()

                assertTrue(harness.hasText("Test framework"))
                assertTrue(harness.hasText("已通过身份校验"))
                assertTrue(harness.hasText("旧引用已拒绝，等待当前会话"))
                assertTrue(harness.hasText("融合设备中心"))
                assertTrue(harness.hasText("device confirmed"))
                assertTrue(harness.hasText("通知查看可用，系统控制入口不可用"))
            }
        }
    }

    @Test
    fun statusPermissionAndBluetoothSettingsActionsReachThePlatform() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 1000) {
                val state = disconnectedAndroidState()
                val model = FakeOpenRoseModel(
                    mutableStateOf(
                        state.copy(
                            platform = state.platform.copy(
                                permission = PermissionState.DENIED,
                                transport = TransportAvailability.Disabled,
                            ),
                        ),
                    ),
                    mutableStateOf(UiSettings(interfaceStyle = style)),
                )
                var permissionRequests = 0
                var settingsRequests = 0
                val platform = androidTestPlatform(
                    MutableStateFlow(IntegrationState()),
                    onPermission = { permissionRequests++ },
                    onBluetoothSettings = { settingsRequests++ },
                )
                val page = mutableStateOf("状态")
                setContent { AndroidTestPage(model, platform, page) }
                val harness = AndroidPageHarness(this, page)
                harness.render()
                harness.clickText("授予蓝牙权限")
                harness.clickText("打开蓝牙设置")
                assertEquals(1, permissionRequests)
                assertEquals(1, settingsRequests)
            }
        }
    }
}
