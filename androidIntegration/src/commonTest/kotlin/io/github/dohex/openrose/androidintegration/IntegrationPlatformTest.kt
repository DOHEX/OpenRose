package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The system-module facts a platform reports reach the integration state, apart from the control state. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IntegrationPlatformTest {
    @Test
    fun anUnimplementedPlatformStartsDisconnectedAndReportsItsModuleLimitation() = runTest {
        val runtime = OwnerRig(this).newRuntime(
            interfaceOpen = false,
            integrationPlatform = IntegrationPlatformState(
                module = ModuleAvailability.Unavailable("System integration is Android only"),
            ),
        )

        assertIs<ConnectionState.Disconnected>(runtime.state.value.connection)
        assertEquals(
            ModuleAvailability.Unavailable("System integration is Android only"),
            runtime.integration.value.platform.module,
        )
    }

    @Test
    fun moduleEvidenceIsObservableWithoutClaimingADeviceConnection() = runTest {
        val runtime = OwnerRig(this).newRuntime(interfaceOpen = false)
        val states = mutableListOf<IntegrationState>()
        val observation = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.integration.take(2).toList(states)
        }

        runtime.updateIntegrationPlatform(IntegrationPlatformState(module = ModuleAvailability.NotEnabled))
        runCurrent()
        observation.join()

        assertEquals(ModuleAvailability.NotEnabled, states.last().platform.module)
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
    }
}
