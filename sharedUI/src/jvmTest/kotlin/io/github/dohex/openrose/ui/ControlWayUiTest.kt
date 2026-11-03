@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.HandoffOutcome
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.OwnerFailureStage
import io.github.dohex.openrose.core.OwnerGeneration
import io.github.dohex.openrose.core.OwnerHolder
import io.github.dohex.openrose.core.OwnerReadiness
import io.github.dohex.openrose.core.OwnerState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SwitchRequest
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlWayUiTest {
    private val moduleUnavailable = ModuleAvailability.Unavailable("兼容框架服务尚未连接")

    @Test
    fun defaultStateShowsOrdinaryControlSelected() {
        forEachStyle(ControlState(platform = platform(ModuleAvailability.NotEnabled))) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.ORDINARY)))
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
        }
    }

    @Test
    fun anUnreadyOwnerNeverClaimsToBeReadyWhileTheModuleIsUnavailable() {
        val starting = ControlState(
            platform = platform(moduleUnavailable),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Starting),
        )
        forEachStyle(starting) { harness ->
            harness.openTab("状态")
            assertTrue(harness.hasText("系统集成未就绪"), "the unready module is reported on the status page")
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Starting)))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
        }
        val moduleStarting = starting.copy(owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Starting))
        forEachStyle(moduleStarting) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            harness.openTab("状态")
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
        }
    }

    @Test
    fun unavailableModuleOwnerShowsStageAndReasonWithoutReadiness() {
        val reason = "fixture: the system Bluetooth exposes no module control endpoint"
        val unavailable = OwnerReadiness.Unavailable(OwnerFailureStage.DISCOVERY, reason)
        val state = ControlState(
            platform = platform(moduleUnavailable),
            owner = OwnerState(selected = ControlWay.MODULE, readiness = unavailable),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            harness.openTab("状态")
            assertTrue(harness.hasTag(ControlWayTags.readiness(unavailable)))
            assertTrue(harness.hasTag(ControlWayTags.failureStage(OwnerFailureStage.DISCOVERY)))
            assertTrue(harness.hasText(reason))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.hasTag(ControlWayTags.connection(ConnectionState.Disconnected)))
        }
    }

    @Test
    fun readyConnectedModuleShowsSelectionOwnerAndConnectionAsSeparateFactsAndTheHolderOnlyInDiagnostics() {
        val holder = OwnerHolder(
            way = ControlWay.MODULE,
            generation = OwnerGeneration("boot-7", 3),
            packageName = "com.android.bluetooth",
            processName = "com.android.bluetooth",
            uid = 1002,
            pid = 4321,
        )
        val connection = ConnectionState.Connected(DeviceId("ctl-way-i5"), 1L, ModelProfile.I5_ZT, ConnectionPhase.READY)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            connection = connection,
            owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready, holder = holder),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            harness.openTab("状态")
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.hasTag(ControlWayTags.connection(connection)))
            assertFalse(harness.hasTag(ControlWayTags.HOLDER), "holder details stay off the status page")
            harness.clickText("运行状态与诊断")
            harness.render()
            val shown = harness.tagTexts(ControlWayTags.HOLDER)
            for (fact in listOf(holder.packageName, holder.processName, holder.generation.toString(), "1002", "4321")) {
                assertTrue(shown.any { fact in it }, "the holder shows $fact")
            }
        }
    }

    @Test
    fun readyOwnerWithoutAnEarphoneConnectionStillReportsTheConnectionSeparately() {
        val connection = ConnectionState.Connecting(DeviceId("ctl-way-i5"), 1L, ConnectionPhase.CONNECTING)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            connection = connection,
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready),
        )
        forEachStyle(state, page = "状态") { harness ->
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.hasTag(ControlWayTags.connection(connection)))
            assertFalse(harness.hasTag(ControlWayTags.connection(ConnectionState.Disconnected)))
        }
    }

    @Test
    fun selectingAnotherWayMovesTheSelectionAndReselectingTheSelectedWayChangesNothing() {
        val state = ControlState(
            platform = platform(ModuleAvailability.NotEnabled),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready),
        )
        forEachStyle(state) { harness ->
            harness.clickTag(ControlWayTags.option(ControlWay.ORDINARY))
            harness.render()
            // The fake host starts a new owner on every selection it receives, so a forwarded reselect would show.
            assertEquals(OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready), harness.host.state.value.owner)

            harness.clickTag(ControlWayTags.option(ControlWay.MODULE))
            harness.render()
            assertEquals(ControlWay.MODULE, harness.host.state.value.owner.selected)
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.ORDINARY)))
            harness.openTab("状态")
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Starting)))
        }
    }

    @Test
    fun blockedSwitchExplainsNothingWasTakenOverAndKeepsBothChoices() {
        val request = SwitchRequest("req-1", ControlWay.MODULE, ControlWay.ORDINARY, OwnerGeneration("boot-7", 3))
        val reason = "fixture: the module release could not be confirmed"
        val blocked = OwnerReadiness.SwitchBlocked(request, OwnerFailureStage.RELEASE, reason)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = blocked),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.SWITCH_FEEDBACK), "settings points at the status page")
            assertTrue(harness.isTagClickable(ControlWayTags.option(ControlWay.ORDINARY)))
            assertTrue(harness.isTagClickable(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.hasText(reason), "settings does not repeat the failure reason")
            harness.openTab("状态")
            assertTrue(harness.hasTag(ControlWayTags.readiness(blocked)))
            assertTrue(harness.hasTag(ControlWayTags.failureStage(OwnerFailureStage.RELEASE)))
            assertTrue(harness.hasText(reason))
            assertTrue(harness.hasTag(ControlWayTags.SWITCH_BLOCKED_NOTICE))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
        }
    }

    @Test
    fun blockedHandoffShowsItsStageAndReasonOnlyInDiagnostics() {
        val request = SwitchRequest("req-2", ControlWay.ORDINARY, ControlWay.MODULE)
        val reason = "fixture: the selection could not be saved"
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(
                selected = ControlWay.ORDINARY,
                readiness = OwnerReadiness.Ready,
                lastHandoff = HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, reason),
            ),
        )
        forEachStyle(state, page = "状态") { harness ->
            assertFalse(harness.hasTag(ControlWayTags.LAST_HANDOFF), "the status page does not list past handoffs")
            harness.clickText("运行状态与诊断")
            harness.render()
            assertTrue(harness.hasTag(ControlWayTags.LAST_HANDOFF))
            assertTrue(harness.hasTag(ControlWayTags.failureStage(OwnerFailureStage.SELECTION_PERSISTENCE)))
            assertTrue(harness.hasText(reason))
        }
    }

    @Test
    fun aCompletedHandoffLeavesNoBlockedRecord() {
        val request = SwitchRequest("req-4", ControlWay.ORDINARY, ControlWay.MODULE)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(
                selected = ControlWay.MODULE,
                readiness = OwnerReadiness.Ready,
                lastHandoff = HandoffOutcome.Completed(request),
            ),
        )
        forEachStyle(state, page = "状态") { harness ->
            harness.clickText("运行状态与诊断")
            harness.render()
            assertFalse(harness.hasTag(ControlWayTags.LAST_HANDOFF))
        }
    }

    @Test
    fun switchingDisablesTheChoices() {
        val request = SwitchRequest("req-3", ControlWay.ORDINARY, ControlWay.MODULE)
        val switching = OwnerReadiness.Switching(request)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = switching),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.SWITCH_FEEDBACK))
            assertTrue(harness.isTagDisabled(ControlWayTags.option(ControlWay.ORDINARY)))
            assertTrue(harness.isTagDisabled(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.isTagClickable(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.isTagClickable(ControlWayTags.option(ControlWay.ORDINARY)))
            assertEquals(ControlWay.ORDINARY, harness.host.state.value.owner.selected, "a disabled choice selects nothing")
            harness.openTab("状态")
            assertTrue(harness.hasTag(ControlWayTags.readiness(switching)))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
        }
    }

    @Test
    fun hostsWithoutAModuleChoiceOfferNoControlWayUiAndNoTapPathToIt() {
        val moduleSelected = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready)
        for (host in listOf(HostPlatform.WINDOWS, HostPlatform.LINUX, HostPlatform.UNKNOWN)) {
            // The callbacks are wired on purpose: only the host's capability may hide the card.
            val desktop = ControlState(platform = desktopPlatform(host), owner = moduleSelected)
            forEachStyle(desktop) { harness ->
                assertFalse(harness.hasTagWithPrefix(ControlWayTags.PREFIX), "no control-way node in Settings on $host")
                harness.openTab("状态")
                assertFalse(harness.hasTagWithPrefix(ControlWayTags.PREFIX), "no control-way node in Status on $host")
                assertEquals(moduleSelected, harness.host.state.value.owner, "no control-way input reached the owner on $host")
            }
        }
        val android = ControlState(platform = platform(ModuleAvailability.Available), owner = moduleSelected)
        forEachStyle(android) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.CARD), "the same state does offer the choice on Android")
        }
    }

    @Test
    fun aHostThatCannotActOnTheChoiceNeverShowsAnInertCard() {
        val android = ControlState(platform = platform(ModuleAvailability.Available))
        forEachStyle(android, wired = false) { harness ->
            assertFalse(harness.hasTagWithPrefix(ControlWayTags.PREFIX), "no inert choice in Settings")
            harness.openTab("状态")
            assertTrue(harness.hasTag(ControlWayTags.connection(ConnectionState.Disconnected)), "Status still reports the connection")
        }
    }

    private fun platform(module: ModuleAvailability) = PlatformState(
        permission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        module = module,
        hostPlatform = HostPlatform.ANDROID,
        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
        controlTransportApplicability = PlatformApplicability.APPLICABLE,
    )

    private fun desktopPlatform(host: HostPlatform) = PlatformState(
        permission = PermissionState.NOT_REQUIRED,
        transport = TransportAvailability.Unavailable("fixture: no desktop transport"),
        module = ModuleAvailability.Available,
        hostPlatform = host,
        systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
        controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
    )

    private fun forEachStyle(base: ControlState, wired: Boolean = true, page: String = "设置", check: suspend (Harness) -> Unit) {
        for (style in InterfaceStyle.entries) {
            withHarness(base.copy(settings = UserSettings(interfaceStyle = style)), wired) { harness ->
                try {
                    harness.render()
                    harness.openTab(page)
                    check(harness)
                } catch (failure: Throwable) {
                    throw AssertionError("style $style: ${failure.message}", failure)
                }
            }
        }
    }

    private fun withHarness(initial: ControlState, wired: Boolean, block: suspend (Harness) -> Unit) = runOpenRoseUiTest(1200, 1000) {
        val host = FakeOwnerHost(initial)
        setContent {
            OpenRoseApp(
                state = host.state.value,
                onSettingsChange = {},
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
                onSelectControlWay = if (wired) host::selectControlWay else null,
            )
        }
        block(Harness(this, host))
    }

    /**
     * Stands in for the application's owner: a selection starts the new owner, which is not ready
     * yet. Tests read the resulting state.
     */
    private class FakeOwnerHost(initial: ControlState) {
        val state = mutableStateOf(initial)

        fun selectControlWay(way: ControlWay) {
            state.value = state.value.copy(owner = OwnerState(selected = way, readiness = OwnerReadiness.Starting))
        }
    }

    private class Harness(scene: OpenRoseUiTestScene, val host: FakeOwnerHost) : OpenRoseUiTestHarness(scene)
}

private fun OpenRoseUiTestHarness.openTab(label: String) {
    clickTab(label)
    render()
}
