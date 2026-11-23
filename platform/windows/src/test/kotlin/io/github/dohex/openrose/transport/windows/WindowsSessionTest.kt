package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.test.*

class WindowsSessionTest {
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
