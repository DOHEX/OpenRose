package io.github.dohex.openrose.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

}
