package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.AudioProfileEvidence
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.RecoverableTransportException
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportFacts
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TerminalTransportException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionRecoveryTest {
    @Test
    fun typedSubscriptionFailureGetsOneFreshBleLinkAndRerunsInitialization() = runTest {
        val transport = RecoveryTransport()
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30, i5WriteIntervalMillis = 0),
        )
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(OperationResult.Success, result)
        assertEquals(2, transport.links.size)
        assertTrue(transport.links.first().closed)
        assertTrue(transport.previousLinksClosedAtEveryConnect)
        assertEquals(emptyList(), transport.links.first().writes)
        assertEquals(13, transport.links.last().writes.size)
        assertEquals(
            TransportRecoveryReason.SUBSCRIPTION,
            session.state.value.connectionRecovery?.reason,
        )
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        val recoveredState = session.state.value
        transport.links.first().deliverLate("09ff00000101011100000164620000ffe2".bytes())
        runCurrent()
        assertEquals(recoveredState, session.state.value)
        session.disconnect()
    }

    @Test
    fun typedNotificationFailureDuringInitializationPreservesItsReasonForRecovery() = runTest {
        val transport = RecoveryTransport(
            profile = ModelProfile.I7_RS_V2,
            failFirstSubscription = false,
            failFirstNotificationCollection = true,
            forceBle = true,
            writeSizes = listOf(512, 10),
        )
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i7Advertisement)
        runCurrent()

        val result = session.connect(i7Advertisement.deviceId)

        assertEquals(OperationResult.Success, result)
        assertEquals(2, transport.links.size)
        assertTrue(transport.links.first().closed)
        assertEquals(TransportRecoveryReason.SERVICES_CHANGED, session.state.value.connectionRecovery?.reason)
        assertEquals(9, transport.links.last().writes.size)
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        session.disconnect()
    }

    @Test
    fun adapterRecoverySupportDoesNotRetryOrSwitchAnSppEndpoint() = runTest {
        val transport = RecoveryTransport(
            profile = ModelProfile.I7_RS_V2,
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            failFirstSubscription = true,
        )
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i7Advertisement)
        runCurrent()

        val result = session.connect(i7Advertisement.deviceId)

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(1, transport.links.size)
        assertEquals(listOf<TransportEndpoint>(TransportEndpoint.SppRfcomm("A1:B2:C3:D4:E5:F6", sppUuid)), transport.endpoints)
        assertNull(session.state.value.connectionRecovery)
    }

    @Test
    fun firstReadWithNoMeaningfulResponseUsesItsSingleBleRetryAndStartsFreshCodec() = runTest {
        val transport = RecoveryTransport(failFirstSubscription = false, firstLinkHasNoResponse = true)
        val session = newSession(transport, queryTimeoutMillis = 10)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(OperationResult.Success, result)
        assertEquals(2, transport.links.size)
        assertEquals(1, transport.links.first().writes.size)
        assertEquals(13, transport.links.last().writes.size)
        assertTrue(transport.previousLinksClosedAtEveryConnect)
        assertEquals("08ee00000001010a0002", transport.links.last().writes.first())
        assertEquals(TransportRecoveryReason.NO_INITIAL_RESPONSE, session.state.value.connectionRecovery?.reason)
        assertEquals((session.state.value.connection as ConnectionState.Connected).sessionId, session.state.value.connectionRecovery?.sessionId)
        session.disconnect()
    }

    @Test
    fun matchingButUnacceptedFirstReplyWaitsToDeadlineThenUsesTheRecoveryBudget() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            firstQueryReply = unacceptedI5BatteryReply,
            firstLinkSilentAfterFirstReply = true,
        )
        val session = newSession(transport, queryTimeoutMillis = 10)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val connecting = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()

        val first = transport.links.single()
        assertEquals(1, first.writes.size)
        assertIs<FieldReading.Unknown>(session.state.value.device?.fields?.get(DeviceField.LEFT_BATTERY))
        assertIs<FieldReading.NotProvided>(session.state.value.device?.fields?.get(DeviceField.RIGHT_BATTERY))
        assertIs<FieldReading.NotProvided>(session.state.value.device?.fields?.get(DeviceField.CASE_BATTERY))
        assertTrue(!connecting.isCompleted)

        advanceTimeBy(10)
        runCurrent()

        assertEquals(OperationResult.Success, connecting.await())
        assertEquals(2, transport.links.size)
        assertEquals(1, first.writes.size)
        assertEquals(13, transport.links.last().writes.size)
        assertEquals(TransportRecoveryReason.NO_INITIAL_RESPONSE, session.state.value.connectionRecovery?.reason)
        session.disconnect()
    }

    @Test
    fun laterMeaningfulStateBeforeFirstQueryDeadlineKeepsTheSameLink() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            firstQueryReply = unacceptedI5BatteryReply,
            firstLinkSilentAfterFirstReply = true,
        )
        val session = newSession(transport, queryTimeoutMillis = 30)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val connecting = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()

        val link = transport.links.single()
        assertEquals(1, link.writes.size)
        assertIs<FieldReading.Unknown>(session.state.value.device?.fields?.get(DeviceField.LEFT_BATTERY))
        assertTrue(!connecting.isCompleted)

        link.silentAfterFirstReply = false
        link.deliverLate("09ff00000106020e000000010020".bytes())
        runCurrent()

        assertEquals(OperationResult.Success, connecting.await())
        assertEquals(1, transport.links.size)
        assertEquals(13, link.writes.size)
        assertNull(session.state.value.connectionRecovery)
        session.disconnect()
    }

    @Test
    fun firstQueryWriteDeadlineUsesTheOneRecoveryBudget() = runTest {
        val writeGate = CompletableDeferred<Unit>()
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            writeGateAtIndex = 0,
            writeGate = writeGate,
        )
        val session = newSession(transport, queryTimeoutMillis = 10)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(OperationResult.Success, result)
        assertEquals(2, transport.links.size)
        assertTrue(transport.links.first().closed)
        assertEquals(1, transport.links.first().writes.size)
        assertEquals(13, transport.links.last().writes.size)
        assertEquals(TransportRecoveryReason.WRITE_FAILED, session.state.value.connectionRecovery?.reason)
        session.disconnect()
    }

    @Test
    fun unclassifiedFirstQueryWriteFailureUsesTheOneRecoveryBudget() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            failWriteAtIndex = 0,
            writeFailure = IllegalStateException("transient gatt write error"),
        )
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))

        assertEquals(2, transport.links.size)
        assertTrue(transport.links.first().closed)
        assertEquals(1, transport.links.first().writes.size)
        assertEquals(13, transport.links.last().writes.size)
        assertEquals(TransportRecoveryReason.WRITE_FAILED, session.state.value.connectionRecovery?.reason)
        session.disconnect()
    }

    @Test
    fun laterInitializationWriteFailureDoesNotUseTheFirstQueryRecoveryBudget() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            failWriteAtIndex = 1,
        )
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(1, transport.links.size)
        assertEquals(2, transport.links.single().writes.size)
        assertNull(session.state.value.connectionRecovery)
    }

    @Test
    fun securityFailureOnFirstQueryDoesNotSpendRecoveryBudget() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            failWriteAtIndex = 0,
            writeFailure = SecurityException("permission revoked"),
        )
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(1, transport.links.size)
        assertNull(session.state.value.connectionRecovery)
    }

    @Test
    fun terminalAdapterFailureOnFirstQueryIsNotUpgradedToRecoverable() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            failWriteAtIndex = 0,
            writeFailure = TerminalTransportException("native write is terminal"),
        )
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = assertIs<OperationResult.Failure>(session.connect(i5Advertisement.deviceId))

        assertEquals(ControlErrorCode.DISCONNECTED, result.error.code)
        assertEquals("native write is terminal", result.error.message)
        assertEquals(1, transport.links.size)
        assertNull(session.state.value.connectionRecovery)
    }

    @Test
    fun noInitialResponseConsumesAtMostOneRecoveryAttempt() = runTest {
        val transport = RecoveryTransport(
            failFirstSubscription = false,
            firstLinkHasNoResponse = true,
            everyLinkHasNoResponse = true,
        )
        val session = newSession(transport, queryTimeoutMillis = 10)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(2, transport.links.size)
        assertTrue(transport.links.all { it.writes.size == 1 })
        assertEquals(TransportRecoveryReason.NO_INITIAL_RESPONSE, session.state.value.connectionRecovery?.reason)
    }

    @Test
    fun typedFailureAfterReadyEndsSessionAndNeverReplaysAnInFlightSetting() = runTest {
        val transport = RecoveryTransport(failFirstSubscription = false)
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        val first = transport.links.single()
        first.initializing = false

        val setting = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        assertTrue(first.writes.size > 13)
        first.failNotifications(RecoverableTransportException(TransportRecoveryReason.SERVICES_CHANGED, "late change"))
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(setting.await()).error.code)
        assertEquals(1, transport.links.size)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)

        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        assertEquals(2, transport.links.size)
        assertEquals(13, transport.links.last().writes.size)
        assertTrue(transport.links.last().writes.none { it.contains("0682") })
        assertNull(session.state.value.connectionRecovery)
        session.disconnect()
    }

    @Test
    fun inFlightSettingDisconnectAndFreshManualSessionIsIsolatedForBothProfiles() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val transport = RecoveryTransport(profile = profile, failFirstSubscription = false, forceBle = profile == ModelProfile.I7_RS_V2)
            val session = newSession(transport)
            val advertisement = if (profile == ModelProfile.I5_ZT) i5Advertisement else i7Advertisement
            session.startScan()
            transport.advertisements.emit(advertisement)
            runCurrent()
            assertEquals(OperationResult.Success, session.connect(advertisement.deviceId), profile.name)

            val oldLink = transport.links.single()
            val originalSessionId = (session.state.value.connection as ConnectionState.Connected).sessionId
            val initialWrites = oldLink.writes.toList()
            oldLink.initializing = false
            val (setting, requested) = if (profile == ModelProfile.I5_ZT) {
                DeviceSetting.NOISE_MODE to FieldValue.Text("normal")
            } else {
                DeviceSetting.GAME_MODE to FieldValue.Toggle(false)
            }
            val pending = async { session.set(setting, requested) }
            runCurrent()
            assertTrue(oldLink.writes.size > initialWrites.size, profile.name)

            session.disconnect()
            assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code, profile.name)
            assertTrue(oldLink.closed, profile.name)
            assertEquals(1, transport.connectCalls, profile.name)

            assertEquals(OperationResult.Success, session.connect(advertisement.deviceId), profile.name)
            assertEquals(2, transport.links.size, profile.name)
            assertEquals(initialWrites, transport.links.last().writes, profile.name)
            assertEquals(1, transport.links.last().subscribeCalls, profile.name)
            assertTrue((session.state.value.connection as ConnectionState.Connected).sessionId > originalSessionId, profile.name)

            val freshState = session.state.value
            val staleReading = if (profile == ModelProfile.I5_ZT) "09ff00000101011100000164620000ffe2" else "dd00040ce4325053aa"
            oldLink.deliverLate(staleReading.bytes())
            runCurrent()
            assertEquals(freshState, session.state.value, profile.name)
            session.disconnect()
        }
    }

    @Test
    fun stateObserverThatEndsLinkImmediatelyAfterReadyCannotLeaveADeadConnectedState() = runTest {
        val transport = RecoveryTransport(failFirstSubscription = false)
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val observer = launch {
            session.state.collect { state ->
                if (state.connection is ConnectionState.Connected) {
                    transport.links.single().failNotifications(
                        RecoverableTransportException(TransportRecoveryReason.SERVICES_CHANGED, "ended at ready"),
                    )
                }
            }
        }

        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        runCurrent()

        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(1, transport.links.size)
        assertNull(session.state.value.connectionRecovery)
        observer.cancel()
    }

    @Test
    fun disconnectDuringRecoveryCancelsReconnectUntilTheUserConnectsAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = RecoveryTransport(secondConnectGate = gate)
        val session = newSession(transport)
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val connection = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()

        assertEquals(2, transport.connectCalls)
        assertEquals(TransportRecoveryReason.SUBSCRIPTION, session.state.value.connectionRecovery?.reason)
        assertEquals(1, transport.links.size)
        session.disconnect()
        gate.complete(Unit)
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(connection.await()).error.code)
        assertEquals(2, transport.connectCalls)
        assertEquals(1, transport.links.size)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertNull(session.state.value.connectionRecovery)

        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        assertEquals(3, transport.connectCalls)
        assertEquals(2, transport.links.size)
        session.disconnect()
    }

    private class RecoveryTransport(
        private val profile: ModelProfile = ModelProfile.I5_ZT,
        private val facts: TransportFacts = TransportFacts(),
        private val failFirstSubscription: Boolean = true,
        private val firstLinkHasNoResponse: Boolean = false,
        private val everyLinkHasNoResponse: Boolean = false,
        private val firstQueryReply: String? = null,
        private val firstLinkSilentAfterFirstReply: Boolean = false,
        private val failFirstNotificationCollection: Boolean = false,
        private val failWriteAtIndex: Int? = null,
        private val writeFailure: Exception? = null,
        private val secondConnectGate: CompletableDeferred<Unit>? = null,
        private val writeGateAtIndex: Int? = null,
        private val writeGate: CompletableDeferred<Unit>? = null,
        private val writeSizes: List<Int> = emptyList(),
        val forceBle: Boolean = false,
    ) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val links = mutableListOf<RecoveryLink>()
        val endpoints = mutableListOf<TransportEndpoint>()
        var connectCalls = 0
        var previousLinksClosedAtEveryConnect = true
        override val supportsInitializationRecovery = true
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?) = facts
        override suspend fun connect(
            deviceId: DeviceId,
            endpoint: TransportEndpoint,
            onPhase: (ConnectionPhase) -> Unit,
        ): ControlConnection {
            connectCalls++
            if (connectCalls == 2) secondConnectGate?.await()
            if (links.any { !it.closed }) previousLinksClosedAtEveryConnect = false
            endpoints += endpoint
            val first = links.isEmpty()
            val link = RecoveryLink(profile,
                maxWriteSize = writeSizes.getOrNull(links.size) ?: if (profile == ModelProfile.I5_ZT) 20 else 512,
                failSubscription = first && failFirstSubscription,
                noResponse = firstLinkHasNoResponse && (first || everyLinkHasNoResponse),
                firstQueryReply = if (first) firstQueryReply else null,
                silentAfterFirstReply = first && firstLinkSilentAfterFirstReply,
                failNotificationCollection = first && failFirstNotificationCollection,
                failWriteAtIndex = if (first) failWriteAtIndex else null,
                writeFailure = if (first) writeFailure else null,
                writeGateAtIndex = if (first) writeGateAtIndex else null,
                writeGate = if (first) writeGate else null)
            links += link
            return link
        }
    }

    private class RecoveryLink(
        private val profile: ModelProfile,
        override val maxWriteSize: Int,
        private val failSubscription: Boolean,
        private val noResponse: Boolean,
        private val firstQueryReply: String?,
        var silentAfterFirstReply: Boolean,
        private val failNotificationCollection: Boolean,
        private val failWriteAtIndex: Int?,
        private val writeFailure: Exception?,
        private val writeGateAtIndex: Int?,
        private val writeGate: CompletableDeferred<Unit>?,
    ) : ControlConnection {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = if (failNotificationCollection) flow {
            throw RecoverableTransportException(TransportRecoveryReason.SERVICES_CHANGED, "synthetic service changed")
        } else incoming.receiveAsFlow()
        val writes = mutableListOf<String>()
        var closed = false
        var initializing = true
        var subscribeCalls = 0

        suspend fun deliverLate(bytes: ByteArray) {
            incoming.send(bytes)
        }

        fun failNotifications(cause: Throwable) {
            incoming.close(cause)
        }

        override suspend fun subscribe() {
            subscribeCalls++
            if (failSubscription) throw RecoverableTransportException(
                TransportRecoveryReason.SUBSCRIPTION,
                "synthetic CCCD failure",
            )
        }

        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.hex()
            val writeIndex = writes.size
            writes += hex
            if (writeIndex == 0 && firstQueryReply != null) {
                incoming.send(firstQueryReply.bytes())
                return
            }
            if (writeIndex == failWriteAtIndex) throw writeFailure ?: RecoverableTransportException(
                TransportRecoveryReason.WRITE_FAILED, "synthetic write failure")
            if (writeIndex == writeGateAtIndex) writeGate?.await()
            if (!initializing) return
            if (silentAfterFirstReply) return
            if (noResponse) return
            if (profile == ModelProfile.I5_ZT) {
                i5Replies[hex.substring(10, 14)]?.let { incoming.send(it.bytes()) }
            } else {
                incoming.send("dd00040ce4".bytes())
                incoming.send("325053aa".bytes())
                incoming.send("dd00040d010502f6aa".bytes())
                incoming.send("dd00020901020e01faaa".bytes())
            }
        }

        override suspend fun close() {
            closed = true
        }
    }

    companion object {
        private val i5Advertisement = Advertisement(
            DeviceId("recovery-i5"),
            manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1)),
        )
        private val i7Advertisement = Advertisement(
            DeviceId("recovery-i7"),
            manufacturerData = mapOf(0xe5b8 to ByteArray(15).also { bytes ->
                bytes[0] = 0x29
                bytes[1] = 0x0f
                bytes[5] = 1
                byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte()).copyInto(bytes, 9)
            }),
        )
        private const val sppUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
        private const val unacceptedI5BatteryReply = "000000000001010000aabb00ff0000ff"
        private val i5Replies = mapOf(
            "0101" to "09ff00000101011100000164620000ffe2",
            "0602" to "09ff00000106020e000000010020",
            "0201" to "09ff00000102010b000118",
            "0603" to "09ff00000106030b00011e",
            "0801" to "09ff00000108010b00011e",
            "0d01" to "09ff0000010d010e00010205002d",
            "0401" to "09ff00000104011200060502040605030746",
            "0a01" to "09ff0000010a010b00001f",
            "0605" to "09ff00000106050e000101010126",
            "0c01" to "09ff0000010c010b000122",
            "0607" to "09ff00000106070b000223",
            "0604" to "09ff00000106040b00001e",
            "0e03" to "09ff0000010e030b000126",
        )

        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun TestScope.newSession(
        transport: RecoveryTransport,
        queryTimeoutMillis: Long = 30,
    ) = ControlSession(
        PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
        initialSettings = UserSettings(forceBle = transport.forceBle),
        transport = transport,
        scope = backgroundScope,
        timing = SessionTiming(queryTimeoutMillis = queryTimeoutMillis, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
    )
}
