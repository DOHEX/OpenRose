package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.core.*
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
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val prefix = "available\t001122334455/45617246726565206935/"
        val facts = MutableStateFlow(prefix + "1")
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
            val connected = withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Connected } }
            assertEquals(FieldValue.Number(100), (connected.device?.fields?.get(DeviceField.LEFT_BATTERY) as? FieldReading.Known)?.value)
            assertEquals(FieldValue.Text("wind"), (connected.device?.fields?.get(DeviceField.NOISE_MODE) as? FieldReading.Known)?.value)
            val requested = FieldValue.Text("transparency")
            assertEquals(OperationResult.Success, runtime.set(DeviceSetting.NOISE_MODE, requested))
            assertEquals(requested, (runtime.state.value.device?.fields?.get(DeviceField.NOISE_MODE) as? FieldReading.Known)?.value)
            facts.value = prefix + "0"
            val disconnected = withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Disconnected } }
            assertNull(disconnected.device)
            facts.value = prefix + "1"
            withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Connected } }
            val unconfirmed = FieldValue.Text("anc")
            assertIs<OperationResult.Failure>(runtime.set(DeviceSetting.NOISE_MODE, unconfirmed))
            assertNotEquals(unconfirmed, (runtime.state.value.device?.fields?.get(DeviceField.NOISE_MODE) as? FieldReading.Known)?.value)
        } finally {
            runtime.disconnect()
            transport.close()
            scope.cancel()
        }
    }
}
