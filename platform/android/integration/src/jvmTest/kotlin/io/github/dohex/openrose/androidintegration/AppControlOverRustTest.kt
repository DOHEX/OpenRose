package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import io.github.dohex.openrose.ffi.kotlincore.KotlinCoreTransport
import io.github.dohex.openrose.ffi.kotlincore.SimulatedDrivers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppControlOverRustTest {
    private class Fixture(val drivers: SimulatedDrivers, scope: CoroutineScope) {
        val ids = AtomicInteger(0)
        val runtime = SwitchingControlRuntime(
            initialPlatformState = platform(),
            initialSettings = UserSettings(),
            store = MemoryOwnerStore(),
            appFactory = { ownerScope, settings, platformState, sessionIdBase ->
                CoreControlRuntime(
                    transport = KotlinCoreTransport(drivers),
                    initialPlatformState = platformState,
                    initialSettings = settings,
                    scope = ownerScope,
                    timing = drivers.fastTiming(),
                    sessionIdBase = sessionIdBase,
                    clock = drivers.fixedClock(),
                )
            },
            moduleLink = { LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "no module host in this test") },
            scope = scope,
            local = LocalOwnerIdentity(APP_PACKAGE, APP_PACKAGE),
            newId = { "id-${ids.incrementAndGet()}" },
        )
    }

    private suspend fun withApp(block: suspend (Fixture) -> Unit) {
        SimulatedDrivers().use { drivers ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                block(Fixture(drivers, scope))
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun <T> StateFlow<T>.eventually(condition: (T) -> Boolean): T = withTimeout(3_000) { first(condition) }

    private suspend fun Fixture.connectedReference(): SessionReference {
        assertEquals(OperationResult.Success, runtime.connect(DeviceId(SimulatedDrivers.DEFAULT_ADDRESS)))
        val connected = runtime.state.eventually { it.connection is ConnectionState.Connected }
        return checkNotNull(connected.readySession)
    }

    @Test
    fun theAppOwnerHoldsTheRustControlOnlyWhileAnAppSessionIsHeld() = runBlocking<Unit> {
        withApp { app ->
            val first = app.runtime.holdAppSession()
            app.runtime.integration.eventually { it.owner.readiness == OwnerReadiness.Ready }
            app.connectedReference()
            val second = app.runtime.holdAppSession()
            first.release()
            delay(200)
            assertTrue(app.runtime.state.value.connection is ConnectionState.Connected)
            assertTrue(app.drivers.gatts.single().closes.isEmpty())

            second.release()
            app.runtime.state.eventually { it.connection is ConnectionState.Disconnected }
            withTimeout(3_000) { while (app.drivers.gatts.single().closes.isEmpty()) delay(10) }
            app.runtime.integration.eventually { it.owner.readiness is OwnerReadiness.Idle }
        }
    }

    @Test
    fun aQuickPanelNoiseSetIsConfirmedOnlyByTheHeadsetReadback() = runBlocking<Unit> {
        withApp { app ->
            val hold = app.runtime.holdAppSession()
            val reference = app.connectedReference()
            val gatt = app.drivers.gatts.single()
            gatt.muted = true
            val written = gatt.writes.size

            val noise = HeadsetCommand.NoiseMode(NoiseMode.ANC)
            val setting = async(Dispatchers.Default) { app.runtime.set(noise, reference.sessionId) }
            withTimeout(3_000) { while (gatt.writes.drop(written).none { it == NOISE_READBACK_QUERY }) delay(5) }
            assertFalse(setting.isCompleted)
            assertEquals("wind", app.runtime.state.value.device?.confirmedNoiseMode?.id)

            gatt.inject(NOISE_REPLY_ANC)
            assertEquals(OperationResult.Success, withTimeout(3_000) { setting.await() })
            app.runtime.state.eventually { it.device?.confirmedNoiseMode?.id == "anc" }

            val stale = assertIs<OperationResult.Failure>(app.runtime.set(noise, reference.sessionId + 1))
            assertEquals(ControlErrorCode.DISCONNECTED, stale.error.code)
            hold.release()
        }
    }

    @Test
    fun aSystemEntryReadsAndSetsThroughTheCoreSessionReference() = runBlocking<Unit> {
        withApp { app ->
            val hold = app.runtime.holdAppSession()
            assertEquals(OperationResult.Success, app.runtime.connectFromSystem(DeviceId(SimulatedDrivers.DEFAULT_ADDRESS)))
            val reference = checkNotNull(app.runtime.state.eventually { it.readySession != null }.readySession)

            val read = assertIs<SessionSnapshotResult.Available>(app.runtime.readSession(reference))
            assertEquals("wind", read.device.confirmedNoiseMode?.id)
            assertTrue(DeviceField.LEFT_BATTERY in read.device.diagnostics.fields)

            assertEquals(OperationResult.Success, app.runtime.set(reference, HeadsetCommand.NoiseMode(NoiseMode.ANC)))
            app.runtime.state.eventually { it.device?.confirmedNoiseMode?.id == "anc" }

            val stale = SessionReference(reference.deviceId, reference.sessionId + 1)
            assertIs<SessionSnapshotResult.Failure>(app.runtime.readSession(stale))
            assertIs<OperationResult.Failure>(app.runtime.set(stale, HeadsetCommand.NoiseMode(NoiseMode.NORMAL)))
            hold.release()
        }
    }

    @Test
    fun platformChangesReachTheRunningCoreOwner() = runBlocking<Unit> {
        withApp { app ->
            val hold = app.runtime.holdAppSession()
            app.runtime.integration.eventually { it.owner.readiness == OwnerReadiness.Ready }

            app.runtime.updatePlatformState(platform(permission = PermissionState.DENIED))
            val denied = assertIs<OperationResult.Failure>(app.runtime.connect(DeviceId(SimulatedDrivers.DEFAULT_ADDRESS)))
            assertEquals(ControlErrorCode.PERMISSION_REQUIRED, denied.error.code)

            app.runtime.updatePlatformState(platform())
            app.connectedReference()
            hold.release()
        }
    }

    private companion object {
        const val NOISE_READBACK_QUERY = "08ee00000006020a0008"
        const val NOISE_REPLY_ANC = "09ff00000106020e000100000020"

        fun platform(permission: PermissionState = PermissionState.GRANTED) = PlatformState(
            permission = permission,
            transport = TransportAvailability.Available,
            hostPlatform = HostPlatform.ANDROID,
            scanPermission = PermissionState.GRANTED,
            userUnlocked = true,
        )
    }
}
