@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.BackgroundControlBlock
import io.github.dohex.openrose.androidintegration.BackgroundServiceFact
import io.github.dohex.openrose.androidintegration.BackgroundServiceFacts
import io.github.dohex.openrose.androidintegration.CompanionAssociation
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.OwnerReadiness
import io.github.dohex.openrose.androidintegration.OwnerState
import io.github.dohex.openrose.androidintegration.PresenceObservation
import io.github.dohex.openrose.androidintegration.RunningNotification
import io.github.dohex.openrose.androidintegration.WakeBand
import io.github.dohex.openrose.androidintegration.WakeFacts
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundControlUiTest {
    @Test
    fun theSwitchSavesTheIntentAndNeverClaimsRunningUntilThePlatformReportsIt() {
        for (style in InterfaceStyle.entries) {
            withHarness(style) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.render()
                assertTrue(harness.hasTag(BackgroundControlTags.SWITCH), "background control is offered in $style")
                assertFalse(harness.hasTagWithPrefix(BackgroundControlTags.STATUS_PREFIX), "a disabled intent has no status in $style")

                harness.clickTag(BackgroundControlTags.SWITCH)
                harness.render()

                assertTrue(harness.integration.value.settings.backgroundControlEnabled, "intent is saved in $style")
                assertTrue(harness.hasTag(BackgroundControlTags.STATUS_NOT_RUNNING), "saved intent is not ready in $style")
                assertFalse(harness.hasTag(BackgroundControlTags.STATUS_RUNNING), "saving never reads as running in $style")

                harness.updateIntegration { it.copy(platform = it.platform.copy(backgroundService = BackgroundServiceFacts(BackgroundServiceFact.Running))) }
                harness.render()
                assertTrue(harness.hasTag(BackgroundControlTags.STATUS_RUNNING), "platform fact shows running in $style")
                assertFalse(harness.hasTag(BackgroundControlTags.STATUS_NOT_RUNNING))

                harness.clickTag(BackgroundControlTags.SWITCH)
                harness.render()
                assertFalse(harness.integration.value.settings.backgroundControlEnabled, "the same switch disables it in $style")
                assertFalse(harness.hasTagWithPrefix(BackgroundControlTags.STATUS_PREFIX), "a disabled intent shows no status again in $style")
            }
        }
    }

    @Test
    fun aMissingBluetoothPermissionIsExplainedWithAGrantActionThatClearsIt() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, settings = IntegrationSettings(backgroundControlEnabled = true), permission = PermissionState.DENIED) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.render()

                val blocked = BackgroundControlTags.blocked(BackgroundControlBlock.BLUETOOTH_PERMISSION)
                assertTrue(harness.hasTag(blocked), "reason is shown in $style")
                assertFalse(harness.hasTag(BackgroundControlTags.STATUS_RUNNING))
                harness.clickTag(BackgroundControlTags.GRANT_BLUETOOTH)
                harness.render()
                assertEquals(PermissionState.GRANTED, harness.state.value.platform.permission, "the grant reaches the host in $style")
                assertFalse(harness.hasTag(blocked), "the block is gone once the host reports the permission in $style")
                assertTrue(harness.hasTag(BackgroundControlTags.STATUS_NOT_RUNNING))
            }
        }
    }

    @Test
    fun restrictedStartAndHiddenNotificationAreDisclosedHonestly() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, settings = IntegrationSettings(backgroundControlEnabled = true)) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.updateIntegration {
                    it.copy(
                        platform = it.platform.copy(
                            backgroundService = BackgroundServiceFacts(
                                BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
                            ),
                        ),
                    )
                }
                harness.render()
                assertTrue(
                    harness.hasTag(BackgroundControlTags.blocked(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)),
                    "restriction is shown in $style",
                )

                harness.updateIntegration {
                    it.copy(
                        platform = it.platform.copy(
                            backgroundService = BackgroundServiceFacts(
                                BackgroundServiceFact.Running,
                                RunningNotification.HIDDEN,
                            ),
                        ),
                    )
                }
                harness.render()
                assertTrue(harness.hasTag(BackgroundControlTags.NOTIFICATION_HIDDEN), "hidden notification is disclosed in $style")
                harness.clickTag(BackgroundControlTags.ALLOW_NOTIFICATION)
                harness.render()
                assertEquals(
                    RunningNotification.VISIBLE,
                    harness.integration.value.platform.backgroundService.notification,
                    "the request reaches the host in $style",
                )
                assertFalse(harness.hasTag(BackgroundControlTags.NOTIFICATION_HIDDEN), "the disclosure ends once the host reports it visible in $style")
                assertTrue(harness.hasTag(BackgroundControlTags.STATUS_RUNNING))
            }
        }
    }

    @Test
    fun aMissingAssociationIsOfferedForAssociationAndNeverShownAsReady() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, settings = IntegrationSettings(backgroundControlEnabled = true)) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.updateIntegration {
                    it.copy(
                        platform = it.platform.copy(
                            backgroundService = BackgroundServiceFacts(
                                wake = WakeFacts(WakeBand.ANDROID_14_PLUS, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING),
                            ),
                        ),
                    )
                }
                harness.render()
                assertTrue(harness.hasTag(BackgroundControlTags.RECOVERY_NEEDS_ASSOCIATION), "gap is stated in $style")
                assertFalse(harness.hasTag(BackgroundControlTags.RECOVERY_ARMED), "incomplete never reads as ready in $style")

                harness.clickTag(BackgroundControlTags.ASSOCIATE)
                harness.render()
                assertEquals(
                    CompanionAssociation.ACTIVE,
                    harness.integration.value.platform.backgroundService.wake.association,
                    "the association request reaches the host in $style",
                )
                assertTrue(harness.hasTag(BackgroundControlTags.RECOVERY_ARMED), "completed prerequisites are reported in $style")
                assertFalse(harness.hasTag(BackgroundControlTags.RECOVERY_NEEDS_ASSOCIATION))

                harness.clickTag(BackgroundControlTags.APP_SETTINGS)
                assertTrue(harness.host.appSettingsShown, "autostart guidance reaches the host in $style")
            }
        }
    }

    @Test
    fun androidEightToElevenNeedsNoAssociationButStillDisclosesAutostart() {
        withHarness(InterfaceStyle.MIUIX, settings = IntegrationSettings(backgroundControlEnabled = true)) { harness ->
            harness.render()
            harness.openPage("设置")
            harness.updateIntegration {
                it.copy(platform = it.platform.copy(backgroundService = BackgroundServiceFacts(wake = WakeFacts(WakeBand.ANDROID_8_11))))
            }
            harness.render()
            assertFalse(harness.hasTag(BackgroundControlTags.ASSOCIATE), "no association is requested before Android 12")
            assertTrue(harness.hasTag(BackgroundControlTags.RECOVERY_ARMED))
            assertTrue(harness.hasTag(BackgroundControlTags.APP_SETTINGS), "autostart guidance stays reachable")
        }
    }

    @Test
    fun moduleControlNeverShowsArmedAutomaticRecovery() {
        val armed = BackgroundServiceFacts(
            wake = WakeFacts(WakeBand.ANDROID_14_PLUS, CompanionAssociation.ACTIVE, PresenceObservation.OBSERVING),
        )
        for (style in InterfaceStyle.entries) {
            withHarness(
                style,
                settings = IntegrationSettings(backgroundControlEnabled = true),
                owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready),
            ) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.updateIntegration { it.copy(platform = it.platform.copy(backgroundService = armed)) }
                harness.render()
                assertFalse(harness.hasTag(BackgroundControlTags.RECOVERY_ARMED), "module control is not ordinary recovery in $style")
                assertFalse(harness.hasTagWithPrefix(BackgroundControlTags.STATUS_PREFIX), "background control is not in force under module control in $style")
                assertTrue(harness.integration.value.settings.backgroundControlEnabled, "the saved intent is kept in $style")

                harness.updateIntegration { it.copy(owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready)) }
                harness.render()
                assertTrue(harness.hasTag(BackgroundControlTags.RECOVERY_ARMED), "the same facts arm recovery under ordinary control in $style")
            }
        }
    }

    @Test
    fun basicPlatformsDoNotOfferBackgroundControl() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 1000) {
                val page = mutableStateOf("设置")
                val model = FakeOpenRoseModel(mutableUiSettings = mutableStateOf(UiSettings(interfaceStyle = style)))
                setContent { AndroidTestPage(model, BasicPlatformUi("Linux"), page) }
                val harness = AndroidPageHarness(this, page)
                harness.render()
                assertFalse(harness.hasTag(BackgroundControlTags.SWITCH))
            }
        }
    }

    private fun withHarness(
        style: InterfaceStyle,
        settings: IntegrationSettings = IntegrationSettings(),
        permission: PermissionState = PermissionState.GRANTED,
        host: HostPlatform = HostPlatform.ANDROID,
        owner: OwnerState = OwnerState(),
        block: suspend (Harness) -> Unit,
    ) = runOpenRoseUiTest(1200, 2600) {
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(
                    permission = permission,
                    transport = TransportAvailability.Available,
                    hostPlatform = host,
                    controlTransportApplicability = PlatformApplicability.APPLICABLE,
                ),
            ),
        )
        val integration = MutableStateFlow(
            IntegrationState(
                settings = settings,
                platform = IntegrationPlatformState(systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE),
                owner = owner,
            ),
        )
        val fake = FakeBackgroundHost(state, integration)
        val page = mutableStateOf("设置")
        val model = FakeOpenRoseModel(state, mutableStateOf(UiSettings(interfaceStyle = style)))
        val platform = androidTestPlatform(
            integration,
            onPermission = fake::grantBluetoothPermission,
            onNotification = fake::showRunningNotification,
            onAssociation = fake::associateEarphones,
            onAppSettings = fake::openAppSettings,
        )
        setContent { AndroidTestPage(model, platform, page) }
        block(Harness(this, page, state, integration, fake))
    }

    /** A host that answers each request the way the platform would, so tests read the resulting facts. */
    private class FakeBackgroundHost(
        private val state: MutableState<ControlState>,
        private val integration: MutableStateFlow<IntegrationState>,
    ) {
        var appSettingsShown = false
            private set

        fun grantBluetoothPermission() {
            state.value = state.value.copy(platform = state.value.platform.copy(permission = PermissionState.GRANTED))
        }

        fun showRunningNotification() = updateService { it.copy(notification = RunningNotification.VISIBLE) }

        fun associateEarphones() = updateService {
            it.copy(wake = it.wake.copy(association = CompanionAssociation.ACTIVE, presence = PresenceObservation.OBSERVING))
        }

        fun openAppSettings() {
            appSettingsShown = true
        }

        private fun updateService(transform: (BackgroundServiceFacts) -> BackgroundServiceFacts) {
            val platform = integration.value.platform
            integration.value = integration.value.copy(platform = platform.copy(backgroundService = transform(platform.backgroundService)))
        }
    }

    private class Harness(
        scene: OpenRoseUiTestScene,
        page: MutableState<String>,
        val state: MutableState<ControlState>,
        val integration: MutableStateFlow<IntegrationState>,
        val host: FakeBackgroundHost,
    ) : AndroidPageHarness(scene, page) {
        fun updateIntegration(transform: (IntegrationState) -> IntegrationState) {
            integration.value = transform(integration.value)
        }
    }
}
