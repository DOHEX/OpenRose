@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlWayUiTest {
    private val moduleUnavailable = ModuleAvailability.Unavailable("兼容框架服务尚未连接")

    @Test
    fun defaultStateShowsOrdinaryControlSelectedAndNotesAnUnavailableModule() = runBlocking {
        forEachStyle(ControlState(platform = platform(ModuleAvailability.NotEnabled))) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.ORDINARY)))
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            assertTrue(harness.hasTag(ControlWayTags.MODULE_NOTE))
            assertFalse(harness.hasTag(ControlWayTags.DISABLE_MODULE))
        }
    }

    @Test
    fun anAvailableModuleNeedsNoNote() = runBlocking {
        forEachStyle(ControlState(platform = platform(ModuleAvailability.Available))) { harness ->
            assertFalse(harness.hasTag(ControlWayTags.MODULE_NOTE))
        }
    }

    @Test
    fun anUnreadyOwnerNeverClaimsToBeReadyWhileTheModuleIsUnavailable() = runBlocking {
        val starting = ControlState(
            platform = platform(moduleUnavailable),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Starting),
        )
        forEachStyle(starting) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Starting)))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.tagTexts(ControlWayTags.MODULE_NOTE).any { moduleUnavailable.reason in it })
        }
        val moduleStarting = starting.copy(owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Starting))
        forEachStyle(moduleStarting) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
        }
    }

    @Test
    fun unavailableModuleOwnerShowsStageAndReasonWithoutReadiness() = runBlocking {
        val reason = "fixture: the system Bluetooth exposes no module control endpoint"
        val unavailable = OwnerReadiness.Unavailable(OwnerFailureStage.DISCOVERY, reason)
        val state = ControlState(
            platform = platform(moduleUnavailable),
            owner = OwnerState(selected = ControlWay.MODULE, readiness = unavailable),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            assertTrue(harness.hasTag(ControlWayTags.readiness(unavailable)))
            assertTrue(harness.hasTag(ControlWayTags.failureStage(OwnerFailureStage.DISCOVERY)))
            assertTrue(harness.hasText(reason))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.hasTag(ControlWayTags.connection(ConnectionState.Disconnected)))
        }
    }

    @Test
    fun readyConnectedModuleShowsSelectionOwnerAndConnectionAsSeparateFactsAndTheRealHolder() = runBlocking {
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
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.hasTag(ControlWayTags.connection(connection)))
            val shown = harness.tagTexts(ControlWayTags.HOLDER)
            for (fact in listOf(holder.packageName, holder.processName, holder.generation.toString(), "1002", "4321")) {
                assertTrue(shown.any { fact in it }, "the holder shows $fact")
            }
        }
    }

    @Test
    fun readyOwnerWithoutAnEarphoneConnectionStillReportsTheConnectionSeparately() = runBlocking {
        val connection = ConnectionState.Connecting(DeviceId("ctl-way-i5"), 1L, ConnectionPhase.CONNECTING)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            connection = connection,
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertTrue(harness.hasTag(ControlWayTags.connection(connection)))
            assertFalse(harness.hasTag(ControlWayTags.connection(ConnectionState.Disconnected)))
        }
    }

    @Test
    fun selectingAnotherWayMovesTheSelectionAndReselectingTheSelectedWayChangesNothing() = runBlocking {
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
            assertTrue(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Starting)))
        }
    }

    @Test
    fun disableModuleControlIsOfferedOnlyWhileModuleControlIsSelected() = runBlocking {
        val ordinary = ControlState(platform = platform(ModuleAvailability.Available))
        forEachStyle(ordinary) { harness -> assertFalse(harness.hasTag(ControlWayTags.DISABLE_MODULE)) }

        val none = ordinary.copy(owner = OwnerState(selected = ControlWay.NONE, readiness = OwnerReadiness.Idle("fixture: disabled")))
        forEachStyle(none) { harness -> assertFalse(harness.hasTag(ControlWayTags.DISABLE_MODULE)) }

        val module = ordinary.copy(owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready))
        forEachStyle(module) { harness ->
            assertTrue(harness.isTagClickable(ControlWayTags.DISABLE_MODULE))
            harness.clickTag(ControlWayTags.DISABLE_MODULE)
            harness.render()
            assertEquals(ControlWay.NONE, harness.host.state.value.owner.selected)
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.ORDINARY)))
            assertFalse(harness.hasTag(ControlWayTags.DISABLE_MODULE))
            assertTrue(harness.hasTag(ControlWayTags.NONE_NOTICE))
        }
    }

    @Test
    fun blockedSwitchExplainsNothingWasTakenOverAndKeepsBothChoices() = runBlocking {
        val request = SwitchRequest("req-1", ControlWay.MODULE, ControlWay.ORDINARY, OwnerGeneration("boot-7", 3))
        val reason = "fixture: the module release could not be confirmed"
        val blocked = OwnerReadiness.SwitchBlocked(request, OwnerFailureStage.RELEASE, reason)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = blocked),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.readiness(blocked)))
            assertTrue(harness.hasTag(ControlWayTags.failureStage(OwnerFailureStage.RELEASE)))
            assertTrue(harness.hasText(reason))
            assertTrue(harness.hasTag(ControlWayTags.SWITCH_BLOCKED_NOTICE))
            assertTrue(harness.isTagClickable(ControlWayTags.option(ControlWay.ORDINARY)))
            assertTrue(harness.isTagClickable(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
        }
    }

    @Test
    fun blockedHandoffShowsItsStageAndReason() = runBlocking {
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
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.LAST_HANDOFF))
            assertTrue(harness.hasTag(ControlWayTags.failureStage(OwnerFailureStage.SELECTION_PERSISTENCE)))
            assertTrue(harness.hasText(reason))
        }
    }

    @Test
    fun aCompletedHandoffLeavesNoBlockedRecord() = runBlocking {
        val request = SwitchRequest("req-4", ControlWay.ORDINARY, ControlWay.MODULE)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(
                selected = ControlWay.MODULE,
                readiness = OwnerReadiness.Ready,
                lastHandoff = HandoffOutcome.Completed(request),
            ),
        )
        forEachStyle(state) { harness -> assertFalse(harness.hasTag(ControlWayTags.LAST_HANDOFF)) }
    }

    @Test
    fun switchingDisablesTheChoices() = runBlocking {
        val request = SwitchRequest("req-3", ControlWay.ORDINARY, ControlWay.MODULE)
        val switching = OwnerReadiness.Switching(request)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = switching),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.readiness(switching)))
            assertTrue(harness.isTagDisabled(ControlWayTags.option(ControlWay.ORDINARY)))
            assertTrue(harness.isTagDisabled(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.hasTag(ControlWayTags.readiness(OwnerReadiness.Ready)))
            assertFalse(harness.isTagClickable(ControlWayTags.option(ControlWay.MODULE)))
            assertFalse(harness.isTagClickable(ControlWayTags.option(ControlWay.ORDINARY)))
            assertEquals(ControlWay.ORDINARY, harness.host.state.value.owner.selected, "a disabled choice selects nothing")
        }
    }

    @Test
    fun disabledModuleControlSaysSoAndStillOffersBothChoices() = runBlocking {
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.NONE, readiness = OwnerReadiness.Idle("fixture: disabled")),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.NONE_NOTICE))
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.ORDINARY)))
            assertFalse(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
            harness.clickTag(ControlWayTags.option(ControlWay.ORDINARY))
            harness.render()
            assertEquals(ControlWay.ORDINARY, harness.host.state.value.owner.selected)
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.ORDINARY)))
            assertFalse(harness.hasTag(ControlWayTags.NONE_NOTICE))
            harness.clickTag(ControlWayTags.option(ControlWay.MODULE))
            harness.render()
            assertEquals(ControlWay.MODULE, harness.host.state.value.owner.selected)
            assertTrue(harness.isTagSelected(ControlWayTags.option(ControlWay.MODULE)))
        }
    }

    @Test
    fun hostsWithoutAModuleChoiceOfferNoControlWayUiAndNoTapPathToIt() = runBlocking {
        val moduleSelected = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready)
        for (host in listOf(HostPlatform.WINDOWS, HostPlatform.LINUX, HostPlatform.UNKNOWN)) {
            // The callbacks are wired on purpose: only the host's capability may hide the card.
            val desktop = ControlState(platform = desktopPlatform(host), owner = moduleSelected)
            forEachStyle(desktop) { harness ->
                assertFalse(harness.hasTagWithPrefix(ControlWayTags.PREFIX), "no control-way node on $host")
                assertEquals(moduleSelected, harness.host.state.value.owner, "no control-way input reached the owner on $host")
            }
        }
        val android = ControlState(platform = platform(ModuleAvailability.Available), owner = moduleSelected)
        forEachStyle(android) { harness ->
            assertTrue(harness.hasTag(ControlWayTags.CARD), "the same state does offer the choice on Android")
        }
    }

    @Test
    fun aHostThatCannotActOnTheChoiceNeverShowsAnInertCard() = runBlocking {
        val android = ControlState(platform = platform(ModuleAvailability.Available))
        forEachStyle(android, wired = false) { harness ->
            assertFalse(harness.hasTagWithPrefix(ControlWayTags.PREFIX))
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

    private suspend fun forEachStyle(base: ControlState, wired: Boolean = true, check: suspend (Harness) -> Unit) {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(base.copy(settings = UserSettings(interfaceStyle = style)), wired)
            try {
                harness.render()
                harness.clickTab("系统集成")
                harness.render()
                check(harness)
            } catch (failure: Throwable) {
                throw AssertionError("style $style: ${failure.message}", failure)
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(initial: ControlState, wired: Boolean): Harness {
        val host = FakeOwnerHost(initial)
        val scene = createOpenRoseUiTestScene(1200, 1000) {
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
                onDisableModuleControl = if (wired) host::disableModuleControl else null,
            )
        }
        return Harness(scene, host)
    }

    /**
     * Stands in for the application's owner: a selection starts the new owner, which is not ready
     * yet, and disabling leaves no control way selected. Tests read the resulting state.
     */
    private class FakeOwnerHost(initial: ControlState) {
        val state = mutableStateOf(initial)

        fun selectControlWay(way: ControlWay) {
            state.value = state.value.copy(owner = OwnerState(selected = way, readiness = OwnerReadiness.Starting))
        }

        fun disableModuleControl() {
            state.value = state.value.copy(
                owner = OwnerState(selected = ControlWay.NONE, readiness = OwnerReadiness.Idle("fixture: module control disabled")),
            )
        }
    }

    private class Harness(scene: ImageComposeScene, val host: FakeOwnerHost) : OpenRoseUiTestHarness(scene)
}
