package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The unified control entry is the only seam the application, the bridge and system entries share.
 * These tests drive the real core session behind the system entry through a controllable transport.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemEntryRuntimeTest {
    @Test
    fun scanThenTargetedConnectReadsTheRealBatteryForI5AndI7() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val transport = RuntimeTransport(profile)
            val runtime = newRuntime(transport)
            val id = DeviceId("rose")

            assertEquals(OperationResult.Success, runtime.startScan())
            runCurrent()
            transport.advertisements.emit(roseAdvertisement(profile, id))
            runCurrent()
            assertEquals(listOf(id), runtime.state.value.candidates.map { it.deviceId })
            assertEquals(OperationResult.Success, runtime.connect(id))

            val device = requireNotNull(runtime.state.value.device)
            val battery = requireNotNull(runtime.presentation.value.battery)
            assertEquals(PresentationReading.Known(100), battery.left)
            assertEquals(PresentationReading.Known(if (profile == ModelProfile.I5_ZT) 98 else 50), battery.right)
            if (profile == ModelProfile.I5_ZT) {
                // The i5 case value 0xff is "not provided", never a percentage.
                assertEquals(PresentationReading.NotProvided, battery.case)
                assertEquals(FieldReading.NotProvided("ff"), device.fields[DeviceField.CASE_BATTERY])
            } else {
                assertEquals(PresentationReading.Known(80), battery.case)
            }
            assertEquals(ScanState.Idle, runtime.state.value.scan)
            runtime.disconnect()
        }
    }

    @Test
    fun aSettingIsOnlyConfirmedWhenTheEarphonesReportTheNewValue() = runTest {
        val transport = RuntimeTransport(ModelProfile.I5_ZT)
        val runtime = newRuntime(transport)
        connectByScan(runtime, transport, ModelProfile.I5_ZT, DeviceId("i5"))
        val link = transport.links.single()
        link.initializing = false

        val pending = async { runtime.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()

        assertFalse(pending.isCompleted)
        assertEquals(SettingStage.WRITTEN, runtime.state.value.settingOperations.last().stage)
        assertEquals(FieldValue.Text("wind"), noiseMode(runtime))
        // Synthetic known normal tail from the independent reverse-engineering receiver table.
        link.deviceReply("09ff00000106020e000001000020")
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        assertEquals(SettingStage.CONFIRMED, runtime.state.value.settingOperations.last().stage)
        assertEquals(FieldValue.Text("normal"), noiseMode(runtime))
        runtime.disconnect()
    }

    @Test
    fun aSettingFromAnEndedSessionOrAnotherDeviceIsRejectedWithoutWriting() = runTest {
        val transport = RuntimeTransport(ModelProfile.I5_ZT)
        val runtime = newRuntime(transport)
        val id = DeviceId("i5")
        connectByScan(runtime, transport, ModelProfile.I5_ZT, id)
        val first = requireNotNull(runtime.state.value.device)
        val oldReference = SessionReference(first.deviceId, first.sessionId)
        runtime.disconnect()
        connectByScan(runtime, transport, ModelProfile.I5_ZT, id)
        val current = requireNotNull(runtime.state.value.device)
        assertNotEquals(first.sessionId, current.sessionId)
        val link = transport.links.last()
        link.initializing = false
        val writesBefore = link.writes.size

        val stale = runtime.set(oldReference, DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))
        val otherDevice = runtime.set(SessionReference(DeviceId("other"), current.sessionId), DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(stale).error.code)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(otherDevice).error.code)
        assertEquals(writesBefore, link.writes.size)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<SessionSnapshotResult.Failure>(runtime.readSession(oldReference)).error.code)
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(SessionReference(id, current.sessionId)))
        runtime.disconnect()
    }

    @Test
    fun aSystemEntryTargetsItsDeviceAndOtherAudioDevicesDoNotReplaceIt() = runTest {
        val a = rosePair("rose-a")
        val b = rosePair("rose-b")
        val transport = RuntimeTransport(ModelProfile.I5_ZT, bonded = listOf(a, b))
        val runtime = newRuntime(transport)

        assertEquals(OperationResult.Success, runtime.connectFromSystem(a.deviceId))
        assertEquals(a.deviceId, assertIs<ConnectionState.Connected>(runtime.state.value.connection).deviceId)
        transport.audioDevice.emit(b.deviceId)
        runCurrent()
        advanceUntilIdle()

        assertEquals(a.deviceId, assertIs<ConnectionState.Connected>(runtime.state.value.connection).deviceId)
        assertEquals(a.deviceId, runtime.state.value.connectionTarget?.deviceId)
        assertEquals(1, transport.links.size)
        runtime.disconnect()
    }

    @Test
    fun anUnrelatedAudioDeviceLeavesTheManagedRoseSessionAlone() = runTest {
        val rose = rosePair("rose")
        val headset = DeviceCandidate(DeviceId("other-headset"), "Other", null, null, "")
        val transport = RuntimeTransport(ModelProfile.I5_ZT, bonded = listOf(rose, headset))
        val runtime = newRuntime(transport)
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        val before = assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        transport.audioDevice.emit(headset.deviceId)
        runCurrent()
        advanceUntilIdle()

        assertEquals(before, runtime.state.value.connection)
        assertEquals(1, transport.links.size)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    @Test
    fun aManualDisconnectEndsTheControlConnectionAndDoesNotReconnect() = runTest {
        val rose = rosePair("rose")
        val transport = RuntimeTransport(ModelProfile.I5_ZT, bonded = listOf(rose))
        val runtime = newRuntime(transport)

        assertEquals(OperationResult.Success, runtime.connect(rose.deviceId))
        runtime.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.single().closed)
        assertEquals(1, transport.links.size)
    }

    @Test
    fun aControlSettingsChangeKeepsTheSameConnectionAndSession() = runTest {
        val transport = RuntimeTransport(ModelProfile.I5_ZT)
        val runtime = newRuntime(transport)
        connectByScan(runtime, transport, ModelProfile.I5_ZT, DeviceId("i5"))
        val before = assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        runtime.updateSettings(runtime.state.value.settings.copy(forceBle = true))
        runtime.updateSettings(runtime.state.value.settings.copy(forceBle = false))

        assertEquals(before, runtime.state.value.connection)
        assertEquals(1, transport.links.size)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    @Test
    fun cancellingTheAddingFlowReleasesOnlyItsUnfinishedConnection() = runTest {
        val rose = rosePair("rose")
        val transport = RuntimeTransport(ModelProfile.I5_ZT, bonded = listOf(rose))
        transport.connectGate = CompletableDeferred()
        val runtime = newRuntime(transport)

        val adding = async(start = CoroutineStart.UNDISPATCHED) { runtime.connectForAddDevice(rose.deviceId) }
        runCurrent()
        assertIs<ConnectionState.Connecting>(runtime.state.value.connection)
        adding.cancel()
        runCurrent()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertEquals(rose.deviceId, runtime.state.value.connectionTarget?.deviceId)
    }

    @Test
    fun aDeviceWithoutModelEvidenceFailsAsUnsupportedAndNeverConnects() = runTest {
        val transport = RuntimeTransport(ModelProfile.I5_ZT)
        val runtime = newRuntime(transport)

        val result = runtime.connect(DeviceId("unknown"))

        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.isEmpty())
    }

    private fun noiseMode(runtime: ControlRuntime): FieldValue = assertIs<FieldReading.Known>(runtime.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value

    private fun TestScope.newRuntime(transport: RuntimeTransport): SystemEntryRuntime = SystemEntryRuntime(
        LocalControlRuntime(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
        ),
        backgroundScope,
    )

    private suspend fun TestScope.connectByScan(runtime: ControlRuntime, transport: RuntimeTransport, profile: ModelProfile, id: DeviceId) {
        runtime.startScan()
        runCurrent()
        transport.advertisements.emit(roseAdvertisement(profile, id))
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connect(id))
    }

    private class RuntimeTransport(
        private val profile: ModelProfile,
        private val bonded: List<DeviceCandidate> = emptyList(),
    ) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val audioDevice = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
        val links = mutableListOf<RuntimeConnection>()
        var connectGate: CompletableDeferred<Unit>? = null

        override val audioConnectedDevice: Flow<DeviceId?> get() = audioDevice
        override suspend fun bondedDevices(): List<DeviceCandidate> = bonded
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
            connectGate?.await()
            return RuntimeConnection(profile).also(links::add)
        }
    }

    private class RuntimeConnection(private val profile: ModelProfile) : ControlConnection {
        override val maxWriteSize = 20
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        val writes = mutableListOf<String>()
        var initializing = true
        var closed = false

        override suspend fun subscribe() = Unit

        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.toHex()
            writes += hex
            if (!initializing) return
            initializationReplies(profile, hex).forEach { incoming.send(it.fromHex()) }
        }

        override suspend fun close() {
            closed = true
            initializing = false
            incoming.close()
        }

        suspend fun deviceReply(hex: String) = incoming.send(hex.fromHex())
    }
}

private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
