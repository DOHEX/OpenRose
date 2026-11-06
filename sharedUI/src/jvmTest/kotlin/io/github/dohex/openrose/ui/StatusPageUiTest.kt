package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusPageUiTest {
    @Test
    fun unavailableDesktopTransportIsExplainedInStatusAndDiagnostics() {
        for (style in InterfaceStyle.entries) {
            val reason = "Desktop Bluetooth transport is unavailable"
            val state = mutableStateOf(
                ControlState(
                    platform = PlatformState(
                        permission = PermissionState.NOT_REQUIRED,
                        transport = TransportAvailability.Unavailable(reason),
                    ),
                ),
            )
            val model = FakeOpenRoseModel(state, mutableStateOf(UiSettings(interfaceStyle = style)))
            val platformUi = BasicPlatformUi("Desktop")
            runOpenRoseUiTest(420, 900) {
                val harness = OpenRoseUiTestHarness(this)
                setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        StatusPage(model, platformUi, onDiagnostics = {})
                    }
                }
                harness.render()
                assertTrue(harness.hasText(reason), "status explains transport unavailability in $style")
                assertFalse(harness.hasText("打开蓝牙设置"), "desktop has no system-settings action in $style")
            }
            runOpenRoseUiTest(420, 900) {
                val harness = OpenRoseUiTestHarness(this)
                setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        PlatformDiagnosticsCard(model, platformUi)
                    }
                }
                harness.render()
                assertTrue(harness.hasText("Desktop"), "diagnostics identify the host in $style")
                assertTrue(harness.hasText(reason), "diagnostics explain transport unavailability in $style")
            }
        }
    }
}
