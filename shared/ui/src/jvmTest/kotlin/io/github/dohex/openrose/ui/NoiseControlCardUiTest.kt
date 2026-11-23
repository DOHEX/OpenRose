@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NoiseControlCardUiTest {
    @Test
    fun materialModeButtonsMorphWhilePressedAndKeepTheConfirmedModeSelected() {
        val state = state(mode = knownMode("anc"))
        runOpenRoseUiTest(420, 500) {
            setContent {
                ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                    NoiseControlCard(state, requireNotNull(state.device)) {}
                }
            }
            val harness = OpenRoseUiTestHarness(this)
            harness.render()
            val resting = harness.snapshot()
            val confirmed = harness.clickableBounds("降噪")
            val option = harness.clickableBounds("通透")
            val rowBackground = resting.getRGB(option.left.toInt() + 2, option.top.toInt() - 1)
            // The checked pill leaves the corner at (8, 3) clear. The idle inner corner leaves (1, 1) clear.
            assertEquals(rowBackground, resting.getRGB(confirmed.left.toInt() + 8, confirmed.top.toInt() + 3))
            val x = option.left.toInt() + 1
            val y = option.top.toInt() + 1
            assertEquals(rowBackground, resting.getRGB(x, y))

            touchInput { down(option.center) }
            harness.render(40)
            assertNotEquals(rowBackground, harness.snapshot().getRGB(x, y), "press narrows the inner corner")
            assertTrue(harness.hasSelectedText("降噪"))
            assertFalse(harness.hasSelectedText("通透"))
            touchInput { up() }
        }
    }

    @Test
    fun levelRowsFollowTheConfirmedNoiseMode() {
        val cases = listOf(
            "anc" to (true to false),
            "transparency" to (false to true),
            "wind" to (false to false),
        )

        for ((mode, expectedRows) in cases) {
            val nodes = renderNodes(state(mode = knownMode(mode)))
            assertEquals(expectedRows.first, nodes.hasText("降噪档位"), "ANC row in $mode")
            assertEquals(expectedRows.second, nodes.hasText("通透档位"), "transparency row in $mode")
            if (expectedRows.first || expectedRows.second) {
                assertTrue(nodes.hasText("1 档"), "first level in $mode")
                assertTrue(nodes.hasText("3 档"), "middle level in $mode")
                assertTrue(nodes.hasText("5 档"), "last level in $mode")
            } else {
                assertFalse(nodes.hasText("1 档"), "no level buttons in $mode")
                assertFalse(nodes.hasText("3 档"), "no level buttons in $mode")
                assertFalse(nodes.hasText("5 档"), "no level buttons in $mode")
            }
        }
    }

    @Test
    fun unconfirmedModeHidesBothLevelRows() {
        val nodes = renderNodes(state(mode = FieldReading.Loading))
        assertFalse(nodes.hasText("降噪档位"))
        assertFalse(nodes.hasText("通透档位"))
    }

    @Test
    fun eachLevelRowUsesItsOwnOptionsAndIsOmittedWhenUnsupported() {
        for (style in InterfaceStyle.entries) {
            val ancOnlyCapabilities = ModelProfile.I5_ZT.capabilities().noiseControl!!.copy(
                ancLevel = NoiseLevelCapabilities(levels = persistentSetOf(2), visibleModes = persistentSetOf("anc")),
                transparencyLevel = null,
            )
            val transparencyUnsupportedNodes = renderNodes(
                state(mode = knownMode("transparency"), noiseCapabilities = ancOnlyCapabilities),
                style,
            )
            assertFalse(transparencyUnsupportedNodes.hasText("通透档位"))

            val ancNodes = renderNodes(state(mode = knownMode("anc"), noiseCapabilities = ancOnlyCapabilities), style)

            assertTrue(ancNodes.hasText("降噪档位"))
            assertFalse(ancNodes.hasText("通透档位"))
            assertTrue(ancNodes.hasText("2 档"))
            assertFalse(ancNodes.hasText("1 档"))
            assertFalse(ancNodes.hasText("3 档"))
            assertFalse(ancNodes.hasText("5 档"))

            val transparencyOnlyCapabilities = ModelProfile.I5_ZT.capabilities().noiseControl!!.copy(
                ancLevel = null,
                transparencyLevel = NoiseLevelCapabilities(levels = persistentSetOf(2, 4), visibleModes = persistentSetOf("transparency")),
            )
            val transparencyNodes = renderNodes(
                state(mode = knownMode("transparency"), noiseCapabilities = transparencyOnlyCapabilities),
                style,
            )

            assertFalse(transparencyNodes.hasText("降噪档位"))
            assertTrue(transparencyNodes.hasText("通透档位"))
            assertTrue(transparencyNodes.hasText("2 档"))
            assertTrue(transparencyNodes.hasText("4 档"))
            assertFalse(transparencyNodes.hasText("1 档"))
            assertFalse(transparencyNodes.hasText("3 档"))
            assertFalse(transparencyNodes.hasText("5 档"))
        }
    }

    @Test
    fun pendingModeChangeKeepsOnlyThePreviouslyConfirmedLevelRow() {
        for (style in InterfaceStyle.entries) {
            val pending = SettingOperation(
                id = 1,
                sessionId = SESSION_ID,
                setting = DeviceSetting.NOISE_MODE,
                requestedValue = FieldValue.Text("wind"),
                stage = SettingStage.WRITTEN,
            )
            val pendingNodes = renderNodes(state(mode = knownMode("anc"), operations = listOf(pending)), style)

            assertTrue(pendingNodes.hasText("降噪档位"))
            assertFalse(pendingNodes.hasText("通透档位"))
            assertTrue(pendingNodes.hasDisabledButton("1 档"))
        }
    }

    private fun renderNodes(
        state: ControlState,
        style: InterfaceStyle = InterfaceStyle.MATERIAL3,
    ): SemanticsSnapshot {
        val device = requireNotNull(state.device)
        lateinit var snapshot: SemanticsSnapshot
        runOpenRoseUiTest(900, 1200) {
            setContent {
                ProvideInterfaceStyle(style, dark = false) {
                    NoiseControlCard(state, device) {}
                }
            }
            renderFor(40)
            val texts = mutableSetOf<String>()
            val disabledTexts = mutableSetOf<String>()
            fun collect(node: SemanticsNode, inheritedDisabled: Boolean = false) {
                val disabled = inheritedDisabled || node.config.contains(SemanticsProperties.Disabled)
                val nodeTexts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                texts += nodeTexts
                if (disabled) disabledTexts += nodeTexts
                node.children.forEach { collect(it, disabled) }
            }
            rootNodes().forEach { collect(it) }
            snapshot = SemanticsSnapshot(texts, disabledTexts)
        }
        return snapshot
    }

    private fun state(
        mode: FieldReading,
        operations: List<SettingOperation> = emptyList(),
        noiseCapabilities: NoiseControlCapabilities? = null,
    ): ControlState {
        val deviceId = DeviceId("noise-card-i5")
        val profile = ModelProfile.I5_ZT
        val device = DeviceState(
            deviceId = deviceId,
            sessionId = SESSION_ID,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = persistentSetOf(DeviceField.NOISE_MODE, DeviceField.ANC_LEVEL, DeviceField.TRANSPARENCY_LEVEL),
                writableSettings = persistentSetOf(DeviceSetting.NOISE_MODE, DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL),
                noiseControl = noiseCapabilities ?: profile.capabilities().noiseControl,
            ),
            fields = persistentMapOf(
                DeviceField.NOISE_MODE to mode,
                // Retain both cached values across modes; only the active capability is rendered.
                DeviceField.ANC_LEVEL to FieldReading.Known(FieldValue.Number(5), "02"),
                DeviceField.TRANSPARENCY_LEVEL to FieldReading.Known(FieldValue.Number(1), "00"),
            ),
        )
        return ControlState(
            platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            connection = ConnectionState.Connected(deviceId, SESSION_ID, profile, ConnectionPhase.READY),
            device = device,
            settingOperations = operations.toPersistentList(),
        )
    }

    private fun knownMode(mode: String) = FieldReading.Known(FieldValue.Text(mode), "")

    private data class SemanticsSnapshot(
        val texts: Set<String>,
        val disabledTexts: Set<String>,
    ) {
        fun hasText(expected: String): Boolean = expected in texts
        fun hasDisabledButton(expected: String): Boolean = expected in disabledTexts
    }

    private companion object {
        const val SESSION_ID = 7L
    }
}
