package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSnapshotTest {
    @Test
    fun currentI5AndI7SessionCanBeReadWithoutChangingProtocolState() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val transport = SnapshotTransport(profile)
            val session = SystemEntryRuntime(
                LocalControlRuntime(
                    PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                    transport = transport,
                    scope = backgroundScope,
                    timing = SessionTiming(i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
                ),
                backgroundScope,
            )
            session.startScan()
            runCurrent()
            val id = if (profile == ModelProfile.I5_ZT) DeviceId("i5") else DeviceId("i7")
            transport.advertisements.emit(advertisement(profile, id))
            runCurrent()
            assertEquals(OperationResult.Success, session.connect(id))

            val device = requireNotNull(session.state.value.device)
            val reference = SessionReference(device.deviceId, device.sessionId)
            val writesBeforeRead = transport.links.single().writes.toList()
            val available = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
            val snapshot = available.device

            assertEquals(device, snapshot)
            assertEquals(session.presentation.value, available.presentation)
            assertEquals(reference, available.presentation.reference)
            assertEquals(PresentationReading.Known(100), available.presentation.battery?.left)
            assertEquals(PresentationReading.Known(if (profile == ModelProfile.I5_ZT) 98 else 50), available.presentation.battery?.right)
            assertEquals(if (profile == ModelProfile.I5_ZT) PresentationReading.NotProvided else PresentationReading.Known(80), available.presentation.battery?.case)
            assertEquals(PresentationReading.Known("wind"), available.presentation.noise?.mode)
            assertEquals(device.capabilities.writableSettings, available.presentation.actions)
            assertEquals(writesBeforeRead, transport.links.single().writes)
            if (profile == ModelProfile.I5_ZT) {
                assertEquals(FieldReading.NotProvided("ff"), snapshot.fields[DeviceField.CASE_BATTERY])
            }
            session.disconnect()
        }
    }

    @Test
    fun aReferenceMustMatchTheReadyDeviceAndCurrentSession() = runTest {
        val profile = ModelProfile.I5_ZT
        val transport = SnapshotTransport(profile)
        val session = newSession(transport)
        val id = DeviceId("i5")
        val initialReference = SessionReference(id, 1)

        assertFailure(ControlErrorCode.NOT_READY, session.readSession(initialReference))
        connect(session, transport, profile, id)
        val firstDevice = requireNotNull(session.state.value.device)
        val firstReference = SessionReference(firstDevice.deviceId, firstDevice.sessionId)

        assertFailure(ControlErrorCode.DISCONNECTED, session.readSession(SessionReference(DeviceId("other"), firstDevice.sessionId)))
        assertIs<SessionSnapshotResult.Available>(session.readSession(firstReference))
        session.disconnect()
        assertFailure(ControlErrorCode.NOT_READY, session.readSession(firstReference))

        connect(session, transport, profile, id)
        val nextDevice = requireNotNull(session.state.value.device)
        assertFalse(nextDevice.sessionId == firstDevice.sessionId)
        assertFailure(ControlErrorCode.DISCONNECTED, session.readSession(firstReference))
        assertIs<SessionSnapshotResult.Available>(session.readSession(SessionReference(id, nextDevice.sessionId)))
        session.disconnect()
    }

    @Test
    fun readDuringPendingSettingAndStyleChangeKeepsTheConfirmedStateAndSession() = runTest {
        val profile = ModelProfile.I5_ZT
        val transport = SnapshotTransport(profile)
        val session = newSession(transport)
        val id = DeviceId("i5")
        connect(session, transport, profile, id)
        val device = requireNotNull(session.state.value.device)
        val reference = SessionReference(device.deviceId, device.sessionId)
        val link = transport.links.single()
        val initialWrites = link.writes.size
        link.initializing = false

        val pending = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.last().stage)

        val beforeConfirmation = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device
        assertEquals(FieldValue.Text("wind"), (beforeConfirmation.fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        assertEquals(initialWrites + 2, link.writes.size)

        session.updateSettings(session.state.value.settings.copy(interfaceStyle = InterfaceStyle.MATERIAL3))
        val afterStyleChange = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device
        assertEquals(device.sessionId, afterStyleChange.sessionId)
        assertEquals(FieldValue.Text("wind"), (afterStyleChange.fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        assertEquals(initialWrites + 2, link.writes.size)
        assertFalse(pending.isCompleted)

        session.updateSettings(session.state.value.settings.copy(islandMode = IslandMode.OFF, focusNotificationEnabled = false))
        val hidden = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertFalse(hidden.presentation.showStatusNotification)
        assertEquals(null, hidden.presentation.connectionPrompt)
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.last().stage)
        assertEquals(beforeConfirmation, hidden.device)
        assertEquals(initialWrites + 2, link.writes.size)
        assertFalse(pending.isCompleted)

        session.updateSettings(session.state.value.settings.copy(islandMode = IslandMode.MODULE, focusNotificationEnabled = true))
        val switched = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertEquals(null, switched.presentation.connectionPrompt, "Mode changes must not replay the connection event")
        assertEquals(beforeConfirmation, switched.device)
        assertEquals(initialWrites + 2, link.writes.size)
        assertFalse(pending.isCompleted)

        link.deviceReply("09ff00000106020e000001000020")
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        val confirmed = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device
        assertEquals(FieldValue.Text("normal"), (confirmed.fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        assertEquals(initialWrites + 2, link.writes.size)
        session.disconnect()
    }

    @Test
    fun mutatingReturnedCollectionsCannotChangeSessionAuthority() = runTest {
        val profile = ModelProfile.I5_ZT
        val transport = SnapshotTransport(profile)
        val session = newSession(transport)
        val id = DeviceId("i5")
        connect(session, transport, profile, id)
        val original = requireNotNull(session.state.value.device)
        val reference = SessionReference(original.deviceId, original.sessionId)
        val available = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        val snapshot = available.device

        @Suppress("UNCHECKED_CAST")
        val detachedActions = available.presentation.actions as MutableSet<DeviceSetting>
        detachedActions.clear()
        assertEquals(original.capabilities.writableSettings, session.presentation.value.actions)

        @Suppress("UNCHECKED_CAST")
        val detachedFields = snapshot.fields as MutableMap<DeviceField, FieldReading>
        detachedFields[DeviceField.LEFT_BATTERY] = FieldReading.Unknown("mutated")
        val firmware = assertIs<FieldReading.Known>(snapshot.fields[DeviceField.FIRMWARE])
        @Suppress("UNCHECKED_CAST")
        val detachedParts = (firmware.value as FieldValue.Firmware).parts as MutableList<Int>
        detachedParts[0] = 99
        @Suppress("UNCHECKED_CAST")
        val detachedCapabilities = snapshot.capabilities.readableFields as MutableSet<DeviceField>
        detachedCapabilities.clear()
        val snapshotNoise = requireNotNull(snapshot.capabilities.noiseControl)
        val snapshotAncLevels = requireNotNull(snapshotNoise.ancLevel)
        @Suppress("UNCHECKED_CAST")
        val detachedAncOptions = snapshotAncLevels.levels as MutableSet<Int>
        tryClearSnapshotCollection(detachedAncOptions)
        @Suppress("UNCHECKED_CAST")
        val detachedAncModes = snapshotAncLevels.visibleModes as MutableSet<String>
        tryClearSnapshotCollection(detachedAncModes)
        val snapshotTransparencyLevels = requireNotNull(snapshotNoise.transparencyLevel)
        @Suppress("UNCHECKED_CAST")
        val detachedTransparencyOptions = snapshotTransparencyLevels.levels as MutableSet<Int>
        tryClearSnapshotCollection(detachedTransparencyOptions)
        @Suppress("UNCHECKED_CAST")
        val detachedTransparencyModes = snapshotTransparencyLevels.visibleModes as MutableSet<String>
        tryClearSnapshotCollection(detachedTransparencyModes)

        val current = requireNotNull(session.state.value.device)
        assertEquals(original.fields, current.fields)
        assertEquals(original.capabilities.readableFields, current.capabilities.readableFields)
        assertEquals(setOf(1, 3, 5), current.capabilities.noiseControl?.ancLevel?.levels)
        assertEquals(setOf("anc"), current.capabilities.noiseControl?.ancLevel?.visibleModes)
        assertEquals(setOf(1, 3, 5), current.capabilities.noiseControl?.transparencyLevel?.levels)
        assertEquals(setOf("transparency"), current.capabilities.noiseControl?.transparencyLevel?.visibleModes)
        assertEquals(FieldReading.NotProvided("ff"), current.fields[DeviceField.CASE_BATTERY])
        assertEquals(
            (original.fields[DeviceField.FIRMWARE] as FieldReading.Known).value,
            (current.fields[DeviceField.FIRMWARE] as FieldReading.Known).value,
        )
        session.disconnect()
    }

    private fun advertisement(profile: ModelProfile, id: DeviceId) = when (profile) {
        ModelProfile.I5_ZT -> Advertisement(id, "i5", manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1)))
        ModelProfile.I7_RS_V2 -> Advertisement(id, "i7", manufacturerData = mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f)))
    }

    private fun kotlinx.coroutines.test.TestScope.newSession(transport: SnapshotTransport) = SystemEntryRuntime(
        LocalControlRuntime(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
        ),
        backgroundScope,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.connect(
        session: SystemEntryRuntime,
        transport: SnapshotTransport,
        profile: ModelProfile,
        id: DeviceId,
    ) {
        session.startScan()
        runCurrent()
        transport.advertisements.emit(advertisement(profile, id))
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(id))
    }

    private fun assertFailure(code: ControlErrorCode, result: SessionSnapshotResult) {
        assertEquals(code, assertIs<SessionSnapshotResult.Failure>(result).error.code)
    }

    private fun <T> tryClearSnapshotCollection(collection: MutableSet<T>) {
        try {
            collection.clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable copied sets are safe too; the live session must remain unchanged either way.
        }
    }

    private class SnapshotTransport(private val profile: ModelProfile) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val links = mutableListOf<SnapshotConnection>()

        override fun scan(): Flow<Advertisement> = advertisements

        override suspend fun connect(
            deviceId: DeviceId,
            endpoint: TransportEndpoint,
            onPhase: (ConnectionPhase) -> Unit,
        ): ControlConnection = SnapshotConnection(profile).also(links::add)
    }

    private class SnapshotConnection(private val profile: ModelProfile) : ControlConnection {
        override val maxWriteSize = 20
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        val writes = mutableListOf<String>()
        var initializing = true

        override suspend fun subscribe() = Unit

        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.toHex()
            writes += hex
            if (!initializing) return
            if (profile == ModelProfile.I5_ZT) {
                i5Replies[hex.substring(10, 14)]?.let { incoming.send(it.fromHex()) }
            } else {
                i7Replies.forEach { incoming.send(it.fromHex()) }
            }
        }

        override suspend fun close() {
            initializing = false
            incoming.close()
        }

        suspend fun deviceReply(hex: String) {
            incoming.send(hex.fromHex())
        }
    }

    private companion object {
        val i5Replies = mapOf(
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
        val i7Replies = listOf(
            "dd0011010100020003000400110012001300140043aa",
            "dd00020700e6aa",
            "dd00020801e8aa",
            "dd00020904ecaa",
            "dd00040ce4325053aa",
            "dd00040d020103f4aa",
            "dd00020e00edaa",
            "dd00022a0009aa",
            "dd00022b000aaa",
            "dd00022c030eaa",
            "dd00022d030faa",
            "dd00022e010eaa",
            "dd00022f000eaa",
            "dd0002320011aa",
            "dd0005360000000018aa",
            "dd000b3e86838100010203040506c5aa",
            "dd0002450125aa",
            "dd0002460328aa",
        )
    }
}

private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
