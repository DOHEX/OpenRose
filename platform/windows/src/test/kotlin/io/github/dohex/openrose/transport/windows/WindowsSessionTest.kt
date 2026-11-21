package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.circulation.CirculationCommand
import io.github.dohex.openrose.circulation.CirculationNode
import io.github.dohex.openrose.circulation.HeadsetEndpoint
import io.github.dohex.openrose.circulation.LoopbackDiscoveryNetwork
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.RemoteReading
import io.github.dohex.openrose.circulation.localCommand
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class WindowsSessionTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun phoneReadsAndControlsTheI5HeldByWindowsThroughItsCurrentGattSession() = runBlocking {
        withWindows { windows, facts ->
            val network = LoopbackDiscoveryNetwork()
            val headset = HeadsetEndpoint(windows)
            CirculationNode(files.newFolder(), "Windows PC", network.discovery(), headset = headset).use { desktop ->
                CirculationNode(files.newFolder(), "Phone", network.discovery()).use { phone ->
                    desktop.start()
                    phone.start()
                    phone.pairQr(desktop.offerPairing().qr)
                    val peer = phone.peers.value.single()
                    assertEquals("Windows PC", peer.name)
                    fun read() = phone.localCommand(CirculationCommand.Read(peer.id)) as RemoteReading
                    fun noise(reading: RemoteReading, mode: NoiseMode) = phone.localCommand(
                        CirculationCommand.SetNoise(requireNotNull(reading.session), mode),
                    ) as RemoteReading

                    val initial = read()
                    assertEquals(NoiseMode.WIND, initial.mode)
                    assertEquals(100, initial.left)
                    assertEquals(98, initial.right)
                    assertNull(initial.case)
                    val audio = windows.state.value.audioDeviceId
                    assertEquals(NoiseMode.TRANSPARENCY, noise(initial, NoiseMode.TRANSPARENCY).mode)
                    assertEquals(NoiseMode.TRANSPARENCY, read().mode)
                    assertEquals(audio, windows.state.value.audioDeviceId)

                    facts.value = FACTS + "0"
                    windows.state.first { it.connection is ConnectionState.Disconnected }
                    assertEquals(RemoteControlStatus.IDLE, read().status)
                    facts.value = FACTS + "1"
                    windows.state.first { (it.connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY }
                    val fresh = read()
                    assertNotEquals(initial.session, fresh.session)
                    assertTrue(noise(initial, NoiseMode.TRANSPARENCY).error != null)
                    assertEquals(NoiseMode.WIND, read().mode)
                    assertTrue("耳机未确认" in noise(fresh, NoiseMode.ANC).error.orEmpty())
                    assertEquals(audio, windows.state.value.audioDeviceId)
                }
            }
        }
    }

    @Test
    fun trayCommandsRequireHeadsetReadbackAndLosingAudioRetiresTheSession() = runBlocking {
        withWindows { runtime, facts ->
            val connected = runtime.state.value
            assertEquals(FieldValue.Number(100), (connected.device?.diagnostics?.fields?.get(DeviceField.LEFT_BATTERY) as? FieldReading.Known)?.value)
            assertEquals(FieldValue.Text("wind"), (connected.device?.diagnostics?.fields?.get(DeviceField.NOISE_MODE) as? FieldReading.Known)?.value)
            val requested = FieldValue.Text("transparency")
            assertEquals(OperationResult.Success, runtime.set(HeadsetCommand.NoiseMode(requireNotNull(NoiseMode.fromId(requested.value)))))
            assertEquals(requested, (runtime.state.value.device?.diagnostics?.fields?.get(DeviceField.NOISE_MODE) as? FieldReading.Known)?.value)
            facts.value = FACTS + "0"
            val disconnected = withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Disconnected } }
            assertNull(disconnected.device)
            facts.value = FACTS + "1"
            withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Connected } }
            val unconfirmed = FieldValue.Text("anc")
            assertIs<OperationResult.Failure>(runtime.set(HeadsetCommand.NoiseMode(requireNotNull(NoiseMode.fromId(unconfirmed.value)))))
            assertNotEquals(unconfirmed, (runtime.state.value.device?.diagnostics?.fields?.get(DeviceField.NOISE_MODE) as? FieldReading.Known)?.value)
        }
    }

    private suspend fun withWindows(test: suspend (ControlRuntime, MutableStateFlow<String>) -> Unit) = withTimeout(12_000) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val facts = MutableStateFlow(FACTS + "1")
        val transport = WindowsControlTransport(scope, facts) { args ->
            check(args == listOf("gatt", "001122334455", "011bf5da-0000-1000-8000-00805f9b34fb"))
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path,
                "-cp",
                File(FakeWinrt::class.java.protectionDomain.codeSource.location.toURI()).path,
                FakeWinrt::class.java.name,
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        }
        val runtime = LocalControlRuntime(
            PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Available, hostPlatform = HostPlatform.WINDOWS),
            transport = transport,
            scope = scope,
            timing = SessionTiming(queryTimeoutMillis = 250, writeIntervalMillis = 0),
        )
        try {
            runtime.state.first { (it.connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY }
            test(runtime, facts)
        } finally {
            runtime.disconnect()
            transport.close()
            scope.cancel()
        }
    }

    private companion object {
        const val FACTS = "available\t001122334455/45617246726565206935/"
    }
}
