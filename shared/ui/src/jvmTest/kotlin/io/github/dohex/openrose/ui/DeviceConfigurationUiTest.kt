package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceConfigurationUiTest {
    @Test
    fun openFeelDetailsAndDiagnosticsDistinguishTheHardwareBranches() {
        val branches = mapOf(
            ModelProfile.OPENFEEL_LDAC_RS_COMMON to "OpenFeel · LDAC",
            ModelProfile.OPENFEEL_LHDC_RS_COMMON to "OpenFeel · LHDC",
            ModelProfile.OPENFEEL_DUAL_RS_COMMON to "OpenFeel · LDAC + LHDC",
        )
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(390, 840) {
                val profile = mutableStateOf(branches.keys.first())
                val initial = connectedI7State()
                setContent {
                    val device = requireNotNull(initial.device).copy(profile = profile.value, capabilities = profile.value.capabilities())
                    val state = initial.copy(
                        device = device,
                        connection = ConnectionState.Connected(device.deviceId, device.sessionId, device.profile),
                    )
                    ProvideInterfaceStyle(style, dark = false) {
                        Column {
                            HeadsetInfo(state, device, {}, {})
                            ConnectionDetailsCard(state)
                        }
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                for ((branch, label) in branches) {
                    profile.value = branch
                    harness.render()
                    assertTrue(harness.hasText("$label · RS Common"))
                    assertTrue(harness.hasText(label))
                }
            }
        }
    }
}
