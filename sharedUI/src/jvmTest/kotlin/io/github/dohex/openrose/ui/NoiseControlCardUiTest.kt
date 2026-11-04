@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
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

class NoiseControlCardUiTest {
    @Test
    fun levelRowsFollowTheConfirmedNoiseMode() {
        val cases = listOf(
            "anc" to (true to false),
            "transparency" to (false to true),
            "wind" to (false to false),
            "normal" to (false to false),
        )

        for (style in InterfaceStyle.entries) {
            for ((mode, expectedRows) in cases) {
                val nodes = renderNodes(state(mode = knownMode(mode)), style)
                assertEquals(expectedRows.first, nodes.hasText("降噪档位"), "ANC row in $mode using $style")
                assertEquals(expectedRows.second, nodes.hasText("通透档位"), "transparency row in $mode using $style")
                if (expectedRows.first || expectedRows.second) {
                    assertTrue(nodes.hasText("1 档"), "first level in $mode using $style")
                    assertTrue(nodes.hasText("3 档"), "middle level in $mode using $style")
                    assertTrue(nodes.hasText("5 档"), "last level in $mode using $style")
                } else {
                    assertFalse(nodes.hasText("1 档"), "no level buttons in $mode using $style")
                    assertFalse(nodes.hasText("3 档"), "no level buttons in $mode using $style")
                    assertFalse(nodes.hasText("5 档"), "no level buttons in $mode using $style")
                }
            }
        }
    }

    @Test
    fun unconfirmedModeHidesBothLevelRows() {
        val readings = listOf(
            FieldReading.Loading,
            FieldReading.Unknown("ff"),
            FieldReading.NotProvided(""),
            FieldReading.Known(FieldValue.Number(3), "03"),
            knownMode("not-a-supported-mode"),
            knownMode("auto"),
        )

        for (reading in readings) {
            val nodes = renderNodes(state(mode = reading))
            assertFalse(nodes.hasText("降噪档位"), "ANC row for $reading")
            assertFalse(nodes.hasText("通透档位"), "transparency row for $reading")
        }
    }

    @Test
    fun eachLevelRowUsesItsOwnOptionsAndIsOmittedWhenUnsupported() {
        val ancOnlyCapabilities = noiseControlCapabilities(ModelProfile.I5_ZT).copy(
            ancLevel = NoiseLevelCapabilities(levels = persistentSetOf(2), visibleModes = persistentSetOf("anc")),
            transparencyLevel = null,
        )
        val transparencyUnsupportedNodes = renderNodes(
            state(mode = knownMode("transparency"), noiseCapabilities = ancOnlyCapabilities),
        )
        assertFalse(transparencyUnsupportedNodes.hasText("通透档位"))

        val ancNodes = renderNodes(state(mode = knownMode("anc"), noiseCapabilities = ancOnlyCapabilities))

        assertTrue(ancNodes.hasText("降噪档位"))
        assertFalse(ancNodes.hasText("通透档位"))
        assertTrue(ancNodes.hasText("2 档"))
        assertFalse(ancNodes.hasText("1 档"))
        assertFalse(ancNodes.hasText("3 档"))
        assertFalse(ancNodes.hasText("5 档"))

        val transparencyOnlyCapabilities = noiseControlCapabilities(ModelProfile.I5_ZT).copy(
            ancLevel = null,
            transparencyLevel = NoiseLevelCapabilities(levels = persistentSetOf(2, 4), visibleModes = persistentSetOf("transparency")),
        )
        val transparencyNodes = renderNodes(
            state(mode = knownMode("transparency"), noiseCapabilities = transparencyOnlyCapabilities),
        )

        assertFalse(transparencyNodes.hasText("降噪档位"))
        assertTrue(transparencyNodes.hasText("通透档位"))
        assertTrue(transparencyNodes.hasText("2 档"))
        assertTrue(transparencyNodes.hasText("4 档"))
        assertFalse(transparencyNodes.hasText("1 档"))
        assertFalse(transparencyNodes.hasText("3 档"))
        assertFalse(transparencyNodes.hasText("5 档"))
    }

    @Test
    fun pendingModeChangeKeepsOnlyThePreviouslyConfirmedLevelRow() {
        val pending = SettingOperation(
            id = 1,
            sessionId = SESSION_ID,
            setting = DeviceSetting.NOISE_MODE,
            requestedValue = FieldValue.Text("wind"),
            stage = SettingStage.WRITTEN,
        )
        val pendingNodes = renderNodes(state(mode = knownMode("anc"), operations = listOf(pending)))

        assertTrue(pendingNodes.hasText("降噪档位"))
        assertFalse(pendingNodes.hasText("通透档位"))
        assertTrue(pendingNodes.hasDisabledButton("1 档"))

        val confirmed = pending.copy(stage = SettingStage.CONFIRMED)
        val confirmedNodes = renderNodes(state(mode = knownMode("wind"), operations = listOf(confirmed)))
        assertFalse(confirmedNodes.hasText("降噪档位"))
        assertFalse(confirmedNodes.hasText("通透档位"))
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
                    NoiseControlCard(state, device) { _, _ -> }
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
                noiseControl = noiseCapabilities ?: noiseControlCapabilities(profile),
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
