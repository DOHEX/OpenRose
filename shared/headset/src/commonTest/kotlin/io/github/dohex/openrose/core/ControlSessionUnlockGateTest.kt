package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionUnlockGateTest {
    private val rose = catalogCandidate("rose", ModelProfile.I5_ZT)

    @Test
    fun nothingConnectsBeforeFirstUnlockEvenWhenTheEarphonesAreAlreadyAudioConnected() = runTest {
        val transport = CatalogTransport(ModelProfile.I5_ZT, listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)

        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.isEmpty())
    }

    @Test
    fun firstUnlockConnectsAnAudioConnectionThatPredatesTheOwnerUsingTheRestoredSettings() = runTest {
        val transport = CatalogTransport(ModelProfile.I5_ZT, listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertTrue(transport.links.isEmpty())

        runtime.updateSettings(UserSettings(forceBle = true))
        runtime.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()

        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(1, transport.links.size)
        assertTrue(runtime.state.value.settings.forceBle)
        runtime.disconnect()
    }

    @Test
    fun anAudioConnectionThatEndedBeforeUnlockDoesNotConnectAtUnlock() = runTest {
        val transport = CatalogTransport(ModelProfile.I5_ZT, listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        transport.audioDevice.emit(rose.deviceId)
        transport.audioDevice.emit(null)
        runCurrent()

        runtime.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.isEmpty())
    }

    @Test
    fun anAudioConnectionAfterUnlockConnectsAndRepeatedUnlockOrAudioEventsKeepOneConnection() = runTest {
        val transport = CatalogTransport(ModelProfile.I5_ZT, listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        runtime.updatePlatformState(androidPlatform(unlocked = true))
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        repeat(3) {
            runtime.updatePlatformState(androidPlatform(unlocked = true))
            transport.audioDevice.emit(rose.deviceId)
            runCurrent()
            advanceUntilIdle()
        }

        assertEquals(1, transport.links.size)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    @Test
    fun aManualDisconnectIsNotUndoneByLaterPlatformRefreshes() = runTest {
        val transport = CatalogTransport(ModelProfile.I5_ZT, listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        runtime.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        runtime.disconnect()
        repeat(3) { runtime.updatePlatformState(androidPlatform(unlocked = true)) }
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertEquals(1, transport.links.size)
    }

    @Test
    fun scanPermissionDoesNotBlockPairedControl() = runTest {
        val runtime = newRuntime(CatalogTransport(ModelProfile.I5_ZT, listOf(rose)), unlocked = true)
        runtime.updatePlatformState(
            PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Available, scanPermission = PermissionState.DENIED),
        )
        runCurrent()

        val denied = assertIs<OperationResult.Failure>(runtime.startScan())
        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, denied.error.code)
        assertEquals(OperationResult.Success, runtime.connect(rose.deviceId))
        runtime.disconnect()
    }

    private fun androidPlatform(unlocked: Boolean) = PlatformState(
        permission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        userUnlocked = unlocked,
    )

    private fun TestScope.newRuntime(transport: CatalogTransport, unlocked: Boolean): ControlRuntime = LocalControlRuntime(
        initialPlatformState = androidPlatform(unlocked),
        transport = transport,
        scope = backgroundScope,
        timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
    )
}
