package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionTargetedConnectionTest {

    @Test
    fun scanWithoutManufacturerDataKeepsKnownIdentityAndNewManufacturerEvidenceWins() = runTest {
        val id = DeviceId("bonded-i5")
        val paired = DeviceCandidate(id, "EarFree i5", null, ModelProfile.I5_ZT, "", "11:22:33:44:55:66")
        val transport = TargetedAudioTransport(bondedList = listOf(paired))
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(Advertisement(id, rssi = -48))
        runCurrent()
        assertEquals(paired.copy(rssi = -48), session.state.value.candidates.single())
        assertEquals(OperationResult.Success, session.connect(id))
        assertEquals(paired.copy(rssi = -48), session.state.value.connectionTarget)
        session.disconnect()

        session.startScan()
        val agate = Advertisement(id, manufacturerData = mapOf(0x8418 to byteArrayOf(5, 5)))
        transport.advertisements.emit(agate)
        runCurrent()
        transport.advertisements.emit(Advertisement(id, rssi = -52))
        runCurrent()
        val scanned = session.state.value.candidates.single()
        assertEquals(ModelProfile.AGATE_F8, scanned.profile)
        assertEquals("18840505", scanned.manufacturerDataHex)
        assertEquals(-52, scanned.rssi)
        assertNull(scanned.classicAddress)
    }

    @Test
    fun grantingPermissionReadsAlreadyConnectedAudioWithoutANewEvent() = runTest {
        val id = DeviceId("audio-before-permission")
        val paired = DeviceCandidate(id, "EarFree i5", null, ModelProfile.I5_ZT, "")
        val platform = PlatformState(PermissionState.REQUIRED, TransportAvailability.Unavailable(TransportUnavailableReason.PermissionRequired))
        val base = TargetedAudioTransport(bondedList = listOf(paired))
        var granted = false
        val transport = object : ControlTransport by base {
            override val audioConnectedDevice = flow {
                emit(if (granted) id else null)
                awaitCancellation()
            }
        }
        val session = ControlSession(platform, transport = transport, scope = backgroundScope, timing = SessionTiming(writeIntervalMillis = 0))
        runCurrent()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        granted = true
        session.updatePlatformState(platform.copy(permission = PermissionState.GRANTED, transport = TransportAvailability.Available))
        runCurrent()
        assertEquals(id, assertIs<ConnectionState.Connected>(session.state.value.connection).deviceId)
        assertTrue(session.release())
        assertIs<OperationResult.Failure>(session.connect(id))
        assertEquals(1, base.connectCalls)
    }

    @Test
    fun targetedConnectSetsTargetIdentitySynchronouslyAndEstablishesSession() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "", "11:22:33:44:55:66")
        val transport = TargetedAudioTransport(bondedList = listOf(candidateA))
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        var targetObservedImmediately: DeviceCandidate? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val launchJob = launch(start = CoroutineStart.UNDISPATCHED) {
                session.connect(idA, pinned = true)
            }
            targetObservedImmediately = session.state.value.connectionTarget
            launchJob.join()
        }
        runCurrent()
        advanceUntilIdle()

        assertNotNull(targetObservedImmediately)
        assertEquals(idA, targetObservedImmediately?.deviceId)

        val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(idA, connected.deviceId)
        assertEquals(idA, session.state.value.device?.deviceId)
        assertEquals(candidateA, session.state.value.connectionTarget)
        assertEquals(1, transport.connectCalls)
    }

    @Test
    fun failedTargetRetainsTargetIdentityAndPreventsAutoConnectingOtherAudioDevice() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")

        val sessionWithPermissionDenied = ControlSession(
            initialPlatformState = PlatformState(PermissionState.DENIED, TransportAvailability.Available),
            transport = TargetedAudioTransport(bondedList = listOf(candidateA, candidateB)),
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        val resultDenied = sessionWithPermissionDenied.connect(idA, pinned = true)
        val failureDenied = assertIs<OperationResult.Failure>(resultDenied)
        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, failureDenied.error.code)
        assertEquals(idA, sessionWithPermissionDenied.state.value.connectionTarget?.deviceId)
        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, sessionWithPermissionDenied.state.value.lastError?.code)

        val unknownId = DeviceId("unknown-rose-model")
        val transport = TargetedAudioTransport(bondedList = listOf(candidateA, candidateB))
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        val resultUnsupported = session.connect(unknownId, pinned = true)
        val failureUnsupported = assertIs<OperationResult.Failure>(resultUnsupported)
        assertEquals(ControlErrorCode.UNSUPPORTED, failureUnsupported.error.code)
        assertEquals(unknownId, session.state.value.connectionTarget?.deviceId)
        assertNull(session.state.value.connectionTarget?.profile)
        assertEquals(ControlErrorCode.UNSUPPORTED, session.state.value.lastError?.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)

        transport.audioDeviceFlow.emit(idB)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(unknownId, session.state.value.connectionTarget?.deviceId)
        assertEquals(0, transport.connectCalls)
    }

    @Test
    fun realConnectTimeoutRetainsTargetAndPreventsAutoConnectingOtherAudioDevice() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")

        val silentConnection = TestAudioConnection(replies = emptyMap())
        val transport = TargetedAudioTransport(
            connectionFactory = { silentConnection },
            bondedList = listOf(candidateA, candidateB),
        )
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(connectTimeoutMillis = 100, queryTimeoutMillis = 50, writeIntervalMillis = 0),
        )

        val result = session.connect(idA, pinned = true)
        val failure = assertIs<OperationResult.Failure>(result)
        assertEquals(ControlErrorCode.TIMEOUT, failure.error.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(idA, session.state.value.connectionTarget?.deviceId)
        assertEquals(ControlErrorCode.TIMEOUT, session.state.value.lastError?.code)
        assertEquals(1, transport.connectCalls)

        transport.audioDeviceFlow.emit(idB)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(idA, session.state.value.connectionTarget?.deviceId)
        assertEquals(1, transport.connectCalls)
    }

    @Test
    fun repeatedSameTargetReusesReadyOrInflightAttempt() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val gate = CompletableDeferred<Unit>()
        val transport = TargetedAudioTransport(
            bondedList = listOf(candidateA),
            connectGate = gate,
        )
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        runCurrent()
        advanceUntilIdle()

        try {
            val firstAttempt = async { session.connect(idA, pinned = true) }
            runCurrent()
            assertEquals(ConnectionPhase.DISCOVERING, (session.state.value.connection as? ConnectionState.Connecting)?.phase)
            assertEquals(1, transport.connectCalls)

            val secondAttempt = async { session.connect(idA, pinned = true) }
            runCurrent()
            assertEquals(1, transport.connectCalls)

            gate.complete(Unit)
            runCurrent()
            advanceUntilIdle()

            assertEquals(OperationResult.Success, firstAttempt.await())
            assertEquals(OperationResult.Success, secondAttempt.await())
            assertIs<ConnectionState.Connected>(session.state.value.connection)
            assertEquals(1, transport.connectCalls)

            val thirdResult = session.connect(idA, pinned = true)
            assertEquals(OperationResult.Success, thirdResult)
            assertIs<ConnectionState.Connected>(session.state.value.connection)
            assertEquals(1, transport.connectCalls)
        } finally {
            if (!gate.isCompleted) {
                gate.complete(Unit)
            }
        }
    }

    @Test
    fun transitionToDifferentTargetTerminatesPreviousSessionAndConnectsNewDevice() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")
        val transport = TargetedAudioTransport(bondedList = listOf(candidateA, candidateB))
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        val resA = session.connect(idA, pinned = true)
        assertEquals(OperationResult.Success, resA)
        assertEquals(idA, (session.state.value.connection as ConnectionState.Connected).deviceId)
        assertEquals(candidateA, session.state.value.connectionTarget)
        val connA = transport.openConnections[0]
        assertFalse(connA.closed)

        val resB = session.connect(idB, pinned = true)
        assertEquals(OperationResult.Success, resB)
        assertTrue(connA.closed)
        assertEquals(idB, (session.state.value.connection as ConnectionState.Connected).deviceId)
        assertEquals(candidateB, session.state.value.connectionTarget)
        assertEquals(2, transport.connectCalls)
    }

    @Test
    fun explicitTargetWinsOverSuspendedOldAudioLookup() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")
        val transport = TargetedAudioTransport(
            bondedList = listOf(candidateA),
        )
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(candidateA), session.state.value.bondedDevices)

        transport.bondedList = listOf(candidateA, candidateB)
        val audioLookupGate = CompletableDeferred<Unit>()
        transport.gateOnNextBondedLookup = audioLookupGate

        try {
            transport.audioDeviceFlow.emit(idB)
            runCurrent()

            val targetResult = session.connect(idA, pinned = true)
            assertEquals(OperationResult.Success, targetResult)
            val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
            assertEquals(idA, connected.deviceId)
            assertEquals(candidateA, session.state.value.connectionTarget)

            audioLookupGate.complete(Unit)
            runCurrent()
            advanceUntilIdle()

            assertEquals(idA, (session.state.value.connection as ConnectionState.Connected).deviceId)
            assertEquals(candidateA, session.state.value.connectionTarget)
            assertEquals(listOf(idA), transport.connectedDeviceIds)
        } finally {
            if (!audioLookupGate.isCompleted) {
                audioLookupGate.complete(Unit)
            }
        }
    }

    @Test
    fun abSelectionRaceTerminatesFirstAttempt() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")
        val gateA = CompletableDeferred<Unit>()
        val transport = TargetedAudioTransport(
            bondedList = listOf(candidateA, candidateB),
            connectGate = gateA,
        )
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        runCurrent()
        advanceUntilIdle()

        try {
            val attemptA = async { session.connect(idA, pinned = true) }
            runCurrent()
            assertEquals(idA, (session.state.value.connection as? ConnectionState.Connecting)?.deviceId)

            transport.connectGate = null
            val attemptB = async { session.connect(idB, pinned = true) }
            runCurrent()
            advanceUntilIdle()

            assertEquals(OperationResult.Success, attemptB.await())
            assertEquals(idB, (session.state.value.connection as ConnectionState.Connected).deviceId)
            assertEquals(candidateB, session.state.value.connectionTarget)

            gateA.complete(Unit)
            runCurrent()
            advanceUntilIdle()

            val resultA = attemptA.await()
            val failureA = assertIs<OperationResult.Failure>(resultA)
            assertEquals(ControlErrorCode.DISCONNECTED, failureA.error.code)

            assertEquals(idB, (session.state.value.connection as ConnectionState.Connected).deviceId)
            assertEquals(candidateB, session.state.value.connectionTarget)
        } finally {
            if (!gateA.isCompleted) {
                gateA.complete(Unit)
            }
        }
    }

    @Test
    fun explicitCancelPreventsResumption() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val gateA = CompletableDeferred<Unit>()
        val transport = TargetedAudioTransport(
            bondedList = listOf(candidateA),
            connectGate = gateA,
        )
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        runCurrent()
        advanceUntilIdle()

        try {
            val attemptA = async { session.connect(idA, pinned = true) }
            runCurrent()
            assertEquals(idA, (session.state.value.connection as? ConnectionState.Connecting)?.deviceId)

            session.disconnect()
            runCurrent()
            assertEquals(ConnectionState.Disconnected, session.state.value.connection)

            gateA.complete(Unit)
            runCurrent()
            advanceUntilIdle()

            val resultA = attemptA.await()
            assertIs<OperationResult.Failure>(resultA)
            assertEquals(ConnectionState.Disconnected, session.state.value.connection)
            assertEquals(idA, session.state.value.connectionTarget?.deviceId)
        } finally {
            if (!gateA.isCompleted) {
                gateA.complete(Unit)
            }
        }
    }

    @Test
    fun manualConnectReplacesPinnedTargetAndManualRetryRetainsTarget() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")
        val transport = TargetedAudioTransport(bondedList = listOf(candidateA, candidateB))
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        session.connect(idA, pinned = true)
        assertEquals(idA, session.state.value.connectionTarget?.deviceId)

        val resB = session.connect(idB)
        assertEquals(OperationResult.Success, resB)
        assertEquals(idB, session.state.value.connectionTarget?.deviceId)
        assertEquals(idB, (session.state.value.connection as ConnectionState.Connected).deviceId)

        transport.audioDeviceFlow.emit(idA)
        runCurrent()
        advanceUntilIdle()
        assertEquals(idB, (session.state.value.connection as ConnectionState.Connected).deviceId)

        session.disconnect()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(idB, session.state.value.connectionTarget?.deviceId)

        transport.audioDeviceFlow.emit(idA)
        runCurrent()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun inheritedAudioSessionDisconnectsOnAudioDisconnectUnderSystemPinWithoutFallback() = runTest {
        val idA = DeviceId("bonded-i5-a")
        val idB = DeviceId("bonded-i5-b")
        val candidateA = DeviceCandidate(idA, "EarFree i5 A", null, ModelProfile.I5_ZT, "")
        val candidateB = DeviceCandidate(idB, "EarFree i5 B", null, ModelProfile.I5_ZT, "")
        val transport = TargetedAudioTransport(bondedList = listOf(candidateA, candidateB))
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        transport.audioDeviceFlow.emit(idA)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(idA, (session.state.value.connection as ConnectionState.Connected).deviceId)

        val result = session.connect(idA, pinned = true)
        assertEquals(OperationResult.Success, result)
        assertEquals(1, transport.connectCalls)
        assertEquals(idA, session.state.value.connectionTarget?.deviceId)

        transport.audioDeviceFlow.emit(null)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(idA, session.state.value.connectionTarget?.deviceId)

        transport.audioDeviceFlow.emit(idB)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(idA, session.state.value.connectionTarget?.deviceId)
        assertEquals(1, transport.connectCalls)
    }

    @Test
    fun replacedAddCompletionKeepsNewAudioSessionBoundToAudioDisconnect() = runTest {
        val id = DeviceId("bonded-i5")
        val candidate = DeviceCandidate(id, "EarFree i5", null, ModelProfile.I5_ZT, "")
        val transport = TargetedAudioTransport(bondedList = listOf(candidate))
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        runCurrent()

        val addContinuations = ArrayDeque<Runnable>()
        val addDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                addContinuations.addLast(block)
            }
        }
        val adding = async(addDispatcher) { session.connectForAddDevice(id) }
        addContinuations.removeFirst().run()
        runCurrent()
        val original = assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertFalse(adding.isCompleted)

        session.disconnect()
        transport.audioDeviceFlow.emit(id)
        runCurrent()
        val replacement = assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertTrue(replacement.sessionId > original.sessionId)
        assertTrue(transport.openConnections.first().closed)

        addContinuations.removeFirst().run()
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(adding.await()).error.code)
        assertEquals(replacement, session.state.value.connection)

        transport.audioDeviceFlow.emit(null)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertTrue(transport.openConnections.last().closed)
    }

    private class TargetedAudioTransport(
        val connectionFactory: (DeviceId) -> TestAudioConnection = { TestAudioConnection(i5Replies) },
        var bondedList: List<DeviceCandidate> = emptyList(),
        var connectGate: CompletableDeferred<Unit>? = null,
        var gateOnNextBondedLookup: CompletableDeferred<Unit>? = null,
    ) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
        val audioDeviceFlow = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
        var connectCalls = 0
        val connectedDeviceIds = mutableListOf<DeviceId>()
        val openConnections = mutableListOf<TestAudioConnection>()

        override val audioConnectedDevice: Flow<DeviceId?> get() = audioDeviceFlow
        override suspend fun bondedDevices(): List<DeviceCandidate> {
            val gate = gateOnNextBondedLookup
            gateOnNextBondedLookup = null
            gate?.await()
            return bondedList
        }

        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
            connectCalls++
            connectedDeviceIds += deviceId
            onPhase(ConnectionPhase.DISCOVERING)
            connectGate?.await()
            val conn = connectionFactory(deviceId)
            openConnections += conn
            return conn
        }
    }

    private class TestAudioConnection(
        val replies: Map<String, String>,
        override val maxWriteSize: Int = 20,
    ) : ControlConnection {
        private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val notifications: Flow<ByteArray> = incoming
        var subscribed = false
        var closed = false
        val writes = mutableListOf<String>()

        override suspend fun subscribe() {
            subscribed = true
        }
        override suspend fun write(bytes: ByteArray) {
            check(subscribed) { "The earbud requires notifications before its first read" }
            val hex = bytes.hex()
            writes += hex
            replies[hex.substring(10, 14)]?.let { incoming.emit(it.bytes()) }
        }
        override suspend fun close() {
            closed = true
        }
    }

    companion object {
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
