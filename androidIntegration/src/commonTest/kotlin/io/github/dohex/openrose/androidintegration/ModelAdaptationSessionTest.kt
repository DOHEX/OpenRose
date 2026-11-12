package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.ControlTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Real sessions driven by documented synthetic notifications, never hardware captures. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelAdaptationSessionTest {
    @Test
    fun headFeelIgnoredFirstQueryDoesNotTriggerBluetoothRecovery() = runTest {
        val (runtime, transport) = runtime(ModelProfile.HEADFEEL_ZT)
        assertEquals(OperationResult.Success, runtime.connect(rosePair("model", ModelProfile.HEADFEEL_ZT).deviceId))
        assertEquals(1, transport.links.size)
        assertEquals(ConnectionPhase.READY, assertIs<ConnectionState.Connected>(runtime.state.value.connection).phase)
        assertEquals(FieldValue.Number(80), known(runtime, DeviceField.CASE_BATTERY))
        assertEquals(null, runtime.state.value.device?.fields?.get(DeviceField.LEFT_BATTERY))
        runtime.disconnect()
    }

    @Test
    fun cambrianRepeatsInitializationOnlyAfterItsDocumentedPause() = runTest {
        val profile = ModelProfile.CAMBRIAN_RS_COMMON
        val (runtime, transport) = runtime(profile)
        val connected = async { runtime.connect(rosePair("model", profile).deviceId) }
        runCurrent()
        val link = transport.links.single()
        assertEquals(listOf("ff000ffa010708090c0d0e122a2b2c2d2e33aa"), link.writes)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, link.writes.size)
        assertFalse(connected.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OperationResult.Success, connected.await())
        assertEquals(listOf("ff000ffa010708090c0d0e122a2b2c2d2e33aa", "ff000ffa010708090c0d0e122a2b2c2d2e33aa"), link.writes)
        assertEquals(FieldValue.Number(90), known(runtime, DeviceField.CASE_BATTERY))
        runtime.disconnect()
    }

    @Test
    fun spatialAudioWaitsForItsOwnFreshReadback() = runTest {
        val profile = ModelProfile.SPORTFEEL_RS_COMMON
        val (runtime, transport) = runtime(profile)
        assertEquals(OperationResult.Success, runtime.connect(rosePair("model", profile).deviceId))
        val link = transport.links.single()
        link.initializing = false
        val request = async { runtime.set(DeviceSetting.SPATIAL_AUDIO, FieldValue.Toggle(true)) }
        runCurrent()
        assertEquals(listOf("ff00023301aa", "ff0002fa33aa"), link.writes.takeLast(2))
        assertEquals(FieldValue.Toggle(false), known(runtime, DeviceField.SPATIAL_AUDIO))
        link.deviceReply("ff00020e01aa")
        runCurrent()
        assertFalse(request.isCompleted)
        link.deviceReply("ff00023301aa")
        runCurrent()
        assertEquals(OperationResult.Success, request.await())
        assertEquals(FieldValue.Toggle(true), known(runtime, DeviceField.SPATIAL_AUDIO))
        runtime.disconnect()
    }

    private fun TestScope.runtime(profile: ModelProfile): Pair<ControlRuntime, HeadsetTransport> {
        val pair = rosePair("model", profile)
        val transport = HeadsetTransport("ordinary", SharedHeadset(), profile, listOf(pair))
        val recovering = object : ControlTransport by transport {
            override val supportsInitializationRecovery = true
        }
        return LocalControlRuntime(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = recovering,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
        ) to transport
    }

    private fun known(runtime: ControlRuntime, field: DeviceField): FieldValue = assertIs<FieldReading.Known>(runtime.state.value.device?.fields?.get(field)).value
}
