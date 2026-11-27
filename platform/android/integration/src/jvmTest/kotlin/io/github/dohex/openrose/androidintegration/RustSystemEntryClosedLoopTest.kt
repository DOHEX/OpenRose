package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.SimulatedDrivers
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class RustSystemEntryClosedLoopTest {
    private suspend fun createConnectedHost(simulated: SimulatedDrivers, hostScope: CoroutineScope): ModuleControlHost {
        val ids = AtomicInteger(0)
        val host = ModuleControlHost(
            packageName = HOST_PACKAGE,
            processName = HOST_PACKAGE,
            incarnation = "rust-host-1",
            authority = testAuthority,
            runtimeFactory = { runtimeScope, settings, platform, sessionIdBase ->
                CoreControlRuntime(
                    transport = ControlTransportAdapter(simulated),
                    initialPlatformState = platform,
                    initialSettings = settings,
                    scope = runtimeScope,
                    timing = simulated.fastTiming(),
                    sessionIdBase = sessionIdBase,
                    clock = simulated.fixedClock(),
                )
            },
            parentScope = hostScope,
            newId = { "lease-${ids.incrementAndGet()}" },
        )
        host.updatePlatform(
            PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
                userUnlocked = true,
                scanPermission = PermissionState.GRANTED,
            ),
        )
        assertTrue(
            host.configure {
                HostConfiguration(
                    userUnlocked = true,
                    settings = HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE)),
                )
            },
        )
        return host
    }

    @Test
    fun aFusionCenterNoiseTapSucceedsOnlyWhenTheRustCoreReceivesHeadsetReadback() = runBlocking<Unit> {
        SimulatedDrivers().use { simulated ->
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = createConnectedHost(simulated, hostScope)
            try {
                val generation = assertIs<HostStatus.Active>(host.status.value).generation
                val lease = assertIs<HostOpenResult.Opened>(host.open(milinkIdentity)).lease.id
                val connection = awaitValue("rust core connected") {
                    host.control.view(milinkIdentity, lease)?.state?.connection as? ConnectionState.Connected
                }
                val reference = SessionReference(connection.deviceId, connection.sessionId)
                assertEquals(DeviceId(SimulatedDrivers.DEFAULT_ADDRESS), reference.deviceId)

                val initialRead = assertIs<HostReadResult.Available>(
                    host.control.readSession(milinkIdentity, lease, generation, reference),
                )
                val initialDevice = assertIs<SessionSnapshotResult.Available>(initialRead.snapshot).device
                assertEquals(0, SystemHeadsetMapping.miLinkAncState(initialDevice.diagnostics.fields))
                assertTrue(initialDevice.diagnostics.fields[DeviceField.LEFT_BATTERY] is FieldReading.Known)

                val gatt = simulated.gatts.single()
                gatt.muted = true
                val writesBefore = gatt.writes.size
                val tap = async(Dispatchers.Default) {
                    host.control.execute(
                        milinkIdentity,
                        lease,
                        generation,
                        HostCommand.Set(reference, HeadsetCommand.NoiseMode(NoiseMode.ANC)),
                    )
                }
                awaitCondition("noise readback query") {
                    gatt.writes.drop(writesBefore).any { it == NOISE_READBACK_QUERY }
                }

                assertFalse(tap.isCompleted)
                val pendingRead = assertIs<HostReadResult.Available>(
                    host.control.readSession(milinkIdentity, lease, generation, reference),
                )
                val pendingFields = assertIs<SessionSnapshotResult.Available>(pendingRead.snapshot).device.diagnostics.fields
                assertEquals(0, SystemHeadsetMapping.miLinkAncState(pendingFields))

                gatt.inject(NOISE_REPLY_ANC)
                val tapped = withTimeoutOrNull(2_000) { tap.await() } ?: fail("timed out waiting for the noise tap to be confirmed")
                assertEquals(HostCallResult.Operation(OperationResult.Success), tapped)
                val confirmedMode = awaitValue("confirmed anc mode") {
                    val read = host.control.readSession(milinkIdentity, lease, generation, reference) as? HostReadResult.Available
                    val snapshot = read?.snapshot as? SessionSnapshotResult.Available
                    snapshot?.let { SystemHeadsetMapping.miLinkAncState(it.device.diagnostics.fields) }?.takeIf { it == 1 }
                }
                assertEquals(1, confirmedMode)
            } finally {
                hostScope.cancel()
            }
        }
    }

    private suspend fun <T : Any> awaitValue(description: String, read: suspend () -> T?): T = withTimeoutOrNull(3_000) {
        var value = read()
        while (value == null) {
            delay(10)
            value = read()
        }
        value
    } ?: fail("timed out waiting for $description")

    private suspend fun awaitCondition(description: String, check: () -> Boolean) {
        withTimeoutOrNull(3_000) {
            while (!check()) {
                delay(5)
            }
        } ?: fail("timed out waiting for $description")
    }

    private companion object {
        const val NOISE_READBACK_QUERY = "08ee00000006020a0008"
        const val NOISE_REPLY_ANC = "09ff00000106020e000100000020"
    }
}
