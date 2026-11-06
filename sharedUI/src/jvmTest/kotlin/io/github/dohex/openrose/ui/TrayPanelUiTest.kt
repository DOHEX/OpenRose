@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayPanelUiTest {
    private val first = candidate("rose-1", ModelProfile.I5_ZT)
    private val second = candidate("rose-2", ModelProfile.I7_RS_V2)

    @Test
    fun connectedPanelShowsBatteryAndNoiseModeWithoutLevelControls() {
        for (style in InterfaceStyle.entries) {
            withTrayPanel(connectedI7State(), style) { panel ->
                panel.render()
                assertTrue(panel.hasText("90%"), "battery readings in $style")
                assertTrue(panel.hasText("降噪模式"), "noise mode choices in $style")
                assertFalse(panel.hasText("降噪档位"), "no ANC level in $style")
                assertFalse(panel.hasText("通透档位"), "no transparency level in $style")
            }
        }
    }

    @Test
    fun selectingAnotherNoiseModeWritesItAndSelectingTheConfirmedOneDoesNot() {
        for (style in InterfaceStyle.entries) {
            val state = connectedI7State()
            val modes = traySummary(state, PrimaryDeviceSelection()).noiseModes
            val confirmed = modes.single { it.selected }
            val other = modes.first { !it.selected }
            withTrayPanel(state, style) { panel ->
                panel.render()

                panel.clickText(confirmed.label)
                assertEquals(emptyList<Pair<DeviceSetting, FieldValue>>(), panel.model.settingWrites, "confirmed mode in $style")

                panel.clickText(other.label)
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.NOISE_MODE to FieldValue.Text(other.mode)),
                    panel.model.settingWrites,
                    "other mode in $style",
                )
            }
        }
    }

    @Test
    fun reconnectConnectsThePrimaryDeviceTheDashboardWouldOffer() {
        for (style in InterfaceStyle.entries) {
            val selection = PrimaryDeviceSelection()
            val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
            selection.select(state, second.deviceId)
            withTrayPanel(state, style, selection) { panel ->
                panel.render()
                panel.clickText("重新连接")
                assertEquals(listOf(second.deviceId), panel.model.connectRequests, "primary device in $style")
            }
        }
    }

    @Test
    fun connectingPanelCanCancelTheAttempt() {
        for (style in InterfaceStyle.entries) {
            val state = disconnectedAndroidState().copy(
                bondedDevices = persistentListOf(first),
                connection = ConnectionState.Connecting(first.deviceId, 1L, ConnectionPhase.CONNECTING),
            )
            withTrayPanel(state, style) { panel ->
                panel.render()
                assertFalse(panel.hasText("重新连接"), "no reconnect while connecting in $style")
                panel.clickText("取消连接")
                assertEquals(1, panel.model.disconnectCount, "cancel in $style")
            }
        }
    }

    @Test
    fun withoutBondedDeviceAddHeadphonesShowsThemInTheMainWindow() {
        for (style in InterfaceStyle.entries) {
            withTrayPanel(disconnectedAndroidState(), style) { panel ->
                panel.render()
                assertFalse(panel.hasText("重新连接"), "nothing to reconnect in $style")
                panel.clickText("添加耳机")
                assertEquals(1L, panel.model.homeNavigationRequests.value, "showHeadphones in $style")
                assertEquals(1, panel.openAppCount, "main window opened in $style")
            }
        }
    }

    @Test
    fun openAppIsAlwaysAvailable() {
        for (style in InterfaceStyle.entries) {
            for (state in listOf(disconnectedAndroidState(), connectedI7State())) {
                withTrayPanel(state, style) { panel ->
                    panel.render()
                    panel.clickText("打开 OpenRose")
                    assertEquals(1, panel.openAppCount, "open app in $style")
                    assertEquals(0L, panel.model.homeNavigationRequests.value, "plain open does not navigate in $style")
                }
            }
        }
    }

    @Test
    fun dashboardAndTrayAgreeOnThePrimaryDeviceAfterSwitchingAndDisconnecting() {
        for (style in InterfaceStyle.entries) {
            val selection = PrimaryDeviceSelection()
            val bonded = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
            withApp(bonded, style, selection) { app ->
                app.render()
                app.clickText("切换")
                app.state.value = bonded.copy(
                    connectionTarget = second,
                    connection = ConnectionState.Connecting(second.deviceId, 1L, ConnectionPhase.CONNECTING),
                )
                app.render()
                app.state.value = bonded
                app.render()

                val trayTarget = traySummary(bonded, selection).reconnectTarget
                // The dashboard offers 切换 only for the device that is not its primary.
                app.clickText("切换")
                val offered = app.model.connectRequests.last()
                assertEquals(
                    listOf(first.deviceId, second.deviceId).single { it != offered },
                    trayTarget,
                    "tray and dashboard primary in $style",
                )
            }
        }
    }

    @Test
    fun aChoiceMadeOnTheComposedDashboardIsReflectedInTheTray() {
        for (style in InterfaceStyle.entries) {
            val selection = PrimaryDeviceSelection()
            val bonded = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
            withApp(bonded, style, selection) { app ->
                app.render()
                val before = traySummary(bonded, selection).reconnectTarget
                app.clickText("切换")
                val chosen = app.model.connectRequests.single()
                app.render()

                assertNotEquals(before, chosen, "the dashboard offered the other device in $style")
                assertEquals(chosen, traySummary(bonded, selection).reconnectTarget, "tray in $style")
            }
        }
    }

    @Test
    fun aChoiceIsForgottenWhenTheBondedListChangesOrTheDashboardLeaves() {
        val third = candidate("rose-3", ModelProfile.I5_ZT)
        for (style in InterfaceStyle.entries) {
            val bonded = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))

            val changedSelection = PrimaryDeviceSelection()
            withApp(bonded, style, changedSelection) { app ->
                app.render()
                app.clickText("切换")
                app.state.value = bonded.copy(bondedDevices = persistentListOf(first, second, third))
                app.render()
                assertEquals(
                    first.deviceId,
                    traySummary(app.state.value, changedSelection).reconnectTarget,
                    "list change in $style",
                )
            }

            val leftSelection = PrimaryDeviceSelection()
            withApp(bonded, style, leftSelection) { app ->
                app.render()
                app.clickText("切换")
                app.state.value = connectedI7State().copy(bondedDevices = bonded.bondedDevices)
                app.render()
                app.state.value = bonded
                app.render()
                assertEquals(
                    first.deviceId,
                    traySummary(bonded, leftSelection).reconnectTarget,
                    "dashboard left composition in $style",
                )
            }
        }
    }

    @Test
    fun noiseModesAreDisabledWhileASettingIsPending() {
        val connected = connectedI7State()
        val selection = PrimaryDeviceSelection()
        val idle = traySummary(connected, selection).noiseModes
        assertTrue(idle.isNotEmpty())
        assertTrue(idle.all { it.enabled })
        assertEquals(1, idle.count { it.selected })

        val pending = connected.copy(
            settingOperations = listOf(
                SettingOperation(
                    id = 1L,
                    sessionId = requireNotNull(connected.device).sessionId,
                    setting = DeviceSetting.NOISE_MODE,
                    requestedValue = FieldValue.Text("transparency"),
                    stage = SettingStage.WRITTEN,
                ),
            ).toPersistentList(),
        )
        val waiting = traySummary(pending, selection).noiseModes
        assertEquals(idle.map { it.mode }, waiting.map { it.mode })
        assertTrue(waiting.none { it.enabled })
        // Only the device's read-back is ever selected, never the requested value.
        assertEquals(idle.single { it.selected }.mode, waiting.single { it.selected }.mode)
    }

    @Test
    fun batteryListsOnlyKnownReadings() {
        val connected = connectedI7State()
        val device = requireNotNull(connected.device)
        val partial = connected.copy(
            device = device.copy(
                fields = device.fields.putAll(
                    persistentMapOf(
                        DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(80), "80"),
                        DeviceField.RIGHT_BATTERY to FieldReading.Unknown("ff"),
                        DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(255), "ff"),
                    ),
                ),
            ),
        )
        val battery = assertNotNull(traySummary(partial, PrimaryDeviceSelection()).battery)
        assertTrue("80%" in battery)
        assertFalse("255" in battery)

        val none = connected.copy(
            device = device.copy(
                fields = device.fields
                    .putAll(
                        persistentMapOf(
                            DeviceField.LEFT_BATTERY to FieldReading.Loading,
                            DeviceField.RIGHT_BATTERY to FieldReading.NotProvided(""),
                            DeviceField.CASE_BATTERY to FieldReading.Unknown("ff"),
                        ),
                    ),
            ),
        )
        assertNull(traySummary(none, PrimaryDeviceSelection()).battery)
    }

    @Test
    fun onlyAReadySessionCountsAsConnected() {
        val selection = PrimaryDeviceSelection()
        val connected = connectedI7State()
        assertTrue(traySummary(connected, selection).connected)
        assertNull(traySummary(connected, selection).reconnectTarget)

        val initializing = connected.copy(
            connection = ConnectionState.Connected(
                requireNotNull(connected.device).deviceId,
                1L,
                ModelProfile.I7_RS_V2,
                ConnectionPhase.INITIALIZING,
            ),
        )
        val summary = traySummary(initializing, selection)
        assertFalse(summary.connected)
        assertTrue(summary.noiseModes.isEmpty())
        assertNull(summary.battery)
        assertNull(summary.reconnectTarget)

        val disconnected = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first))
        assertEquals(first.deviceId, traySummary(disconnected, selection).reconnectTarget)
        assertFalse(traySummary(disconnected, selection).addDevice)
        assertTrue(traySummary(disconnectedAndroidState(), selection).addDevice)
    }

    private fun withTrayPanel(
        initialState: ControlState,
        style: InterfaceStyle,
        selection: PrimaryDeviceSelection = PrimaryDeviceSelection(),
        block: suspend (TrayHarness) -> Unit,
    ) = runOpenRoseUiTest(360, 440) {
        val model = FakeOpenRoseModel(
            mutableStateOf(initialState),
            mutableStateOf(UiSettings(interfaceStyle = style, appearance = Appearance.LIGHT)),
        )
        val panel = TrayHarness(this, model)
        setContent { TrayPanel(model, selection, onOpenApp = { panel.openAppCount++ }) }
        block(panel)
    }

    private class TrayHarness(scene: OpenRoseUiTestScene, val model: FakeOpenRoseModel) : OpenRoseUiTestHarness(scene) {
        var openAppCount = 0
    }

    private class AppHarness(
        scene: OpenRoseUiTestScene,
        val state: androidx.compose.runtime.MutableState<ControlState>,
        val model: FakeOpenRoseModel,
    ) : OpenRoseUiTestHarness(scene)

    /** The main window sharing [selection] with the tray; connecting does nothing, so the dashboard stays composed. */
    private fun withApp(
        initialState: ControlState,
        style: InterfaceStyle,
        selection: PrimaryDeviceSelection,
        block: suspend (AppHarness) -> Unit,
    ) = runOpenRoseUiTest(420, 900) {
        val state = mutableStateOf(initialState)
        val model = FakeOpenRoseModel(state, mutableStateOf(UiSettings(interfaceStyle = style)))
        setContent { OpenRoseApp(model, BasicPlatformUi("Test"), primaryDeviceSelection = selection) }
        block(AppHarness(this, state, model))
    }

    private fun candidate(id: String, profile: ModelProfile) = DeviceCandidate(
        deviceId = DeviceId(id),
        name = "Device $id",
        rssi = null,
        profile = profile,
        manufacturerDataHex = "",
    )
}
