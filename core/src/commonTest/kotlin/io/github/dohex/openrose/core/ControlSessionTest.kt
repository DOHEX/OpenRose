package io.github.dohex.openrose.core

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class ControlSessionTest {
    @Test
    fun anUnimplementedPlatformStartsDisconnectedAndReportsItsLimitations() {
        val session = ControlSession(
            initialPlatformState = PlatformState(
                permission = PermissionState.NOT_REQUIRED,
                transport = TransportAvailability.Unavailable("Linux transport is not implemented"),
            ),
        )

        val state = session.state.value
        assertIs<ConnectionState.Disconnected>(state.connection)
        assertEquals(PermissionState.NOT_REQUIRED, state.platform.permission)
        assertEquals(
            TransportAvailability.Unavailable("Linux transport is not implemented"),
            state.platform.transport,
        )
    }

    @Test
    fun defaultSettingsUseOfficialIslandAndIndependentFocusNotification() {
        val session = ControlSession(PlatformState(permission = PermissionState.REQUIRED))

        val settings = session.state.value.settings
        assertEquals(InterfaceStyle.MIUIX, settings.interfaceStyle)
        assertEquals(Appearance.SYSTEM, settings.appearance)
        assertEquals(IslandMode.OFFICIAL, settings.islandMode)
        assertEquals(true, settings.focusNotificationEnabled)
        assertEquals(setOf(IslandTrigger.CONNECTION), settings.islandTriggers)
    }

    @Test
    fun changingStyleKeepsTheSessionPlatformAndIndependentAppearance() {
        val session = ControlSession(PlatformState(permission = PermissionState.GRANTED))
        val observedState = session.state
        val platform = observedState.value.platform

        session.updateSettings(
            observedState.value.settings.copy(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.DARK,
            ),
        )

        assertSame(observedState, session.state)
        assertEquals(InterfaceStyle.MATERIAL3, observedState.value.settings.interfaceStyle)
        assertEquals(Appearance.DARK, observedState.value.settings.appearance)
        assertEquals(platform, observedState.value.platform)
        assertEquals(ConnectionState.Disconnected, observedState.value.connection)
    }

    @Test
    fun anExplicitEmptyTriggerSetSurvivesLoadingAndFurtherSettingsChanges() {
        val session = ControlSession(
            initialPlatformState = PlatformState(permission = PermissionState.REQUIRED),
            initialSettings = UserSettings(islandTriggers = persistentSetOf()),
        )

        assertEquals(emptySet(), session.state.value.settings.islandTriggers)
        session.updateSettings(session.state.value.settings.copy(islandMode = IslandMode.OFF))

        assertEquals(emptySet(), session.state.value.settings.islandTriggers)
        assertEquals(true, session.state.value.settings.focusNotificationEnabled)
    }

    @Test
    fun platformEvidenceIsObservableWithoutClaimingADeviceConnection() = runBlocking {
        val session = ControlSession(PlatformState(permission = PermissionState.REQUIRED))
        val states = mutableListOf<ControlState>()
        val observation = launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.take(2).toList(states)
        }

        session.updatePlatformState(
            PlatformState(
                permission = PermissionState.DENIED,
                transport = TransportAvailability.Disabled,
            ),
        )
        observation.join()

        assertEquals(PermissionState.REQUIRED, states.first().platform.permission)
        assertEquals(PermissionState.DENIED, states.last().platform.permission)
        assertEquals(TransportAvailability.Disabled, states.last().platform.transport)
        assertEquals(ConnectionState.Disconnected, states.last().connection)
    }

    @Test
    fun changingAMutableSettingsInputCannotSilentlyChangeObservedSessionState() {
        val triggers = mutableSetOf(IslandTrigger.CONNECTION)
        val session = ControlSession(
            initialPlatformState = PlatformState(permission = PermissionState.NOT_REQUIRED),
            initialSettings = UserSettings(islandTriggers = triggers.toPersistentSet()),
        )

        triggers.clear()
        assertEquals(setOf(IslandTrigger.CONNECTION), session.state.value.settings.islandTriggers)

        session.updateSettings(UserSettings(islandTriggers = persistentSetOf()))
        val updatedTriggers = mutableSetOf(IslandTrigger.CONNECTION)
        session.updateSettings(UserSettings(islandTriggers = updatedTriggers.toPersistentSet()))
        updatedTriggers.clear()
        assertEquals(setOf(IslandTrigger.CONNECTION), session.state.value.settings.islandTriggers)
    }
}
