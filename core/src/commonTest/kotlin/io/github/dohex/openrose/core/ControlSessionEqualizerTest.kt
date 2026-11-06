package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Session seam with independent TX literals and explicitly synthetic setting RX. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionEqualizerTest {
    @Test
    fun i5PopEqIsWrittenAndReadBeforeItBecomesConfirmed() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        val pending = async { session.set(DeviceSetting.EQ, FieldValue.Text("pop")) }
        runCurrent()

        // RoseLink 3.8.0 fixed ZtSender extraction, not this project's frame builder.
        assertEquals(listOf("08ee00000002810b000084", "08ee00000002010a0003"), link.writes.drop(13))
        assertFalse(pending.isCompleted)
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.single().stage)
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        link.incoming.send("09ff00000102010b000017".bytes())
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        assertEquals(SettingStage.CONFIRMED, session.state.value.settingOperations.single().stage)
        assertEquals(FieldValue.Text("pop"), reading(session, DeviceField.EQ))
        session.disconnect()
    }

    @Test
    fun i5GameOnAndOffUseTheOppositeWirePolarityAndWaitForReadback() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        for ((enabled, literal, reply) in listOf(
            Triple(true, "08ee00000006830b00008a", "09ff00000106030b00001d"),
            Triple(false, "08ee00000006830b00018b", "09ff00000106030b00011e"),
        )) {
            val before = reading(session, DeviceField.GAME_MODE)
            val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(enabled)) }
            runCurrent()
            assertEquals(listOf(literal, "08ee00000006030a0009"), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            assertEquals(before, reading(session, DeviceField.GAME_MODE))
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Toggle(enabled), reading(session, DeviceField.GAME_MODE))
        }
        session.disconnect()
    }

    @Test
    fun allFourI5PresetsMatchIndependentCommandsAndDeviceReadbacks() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        assertEquals(persistentSetOf("pop", "hifi", "rock", "light"), session.state.value.device?.capabilities?.equalizer?.presets)
        for ((preset, literal, reply) in listOf(
            Triple("pop", "08ee00000002810b000084", "09ff00000102010b000017"),
            Triple("hifi", "08ee00000002810b000185", "09ff00000102010b000118"),
            Triple("rock", "08ee00000002810b000286", "09ff00000102010b000219"),
            Triple("light", "08ee00000002810b000387", "09ff00000102010b00031a"),
        )) {
            val before = reading(session, DeviceField.EQ)
            val pending = async { session.set(DeviceSetting.EQ, FieldValue.Text(preset)) }
            runCurrent()
            assertEquals(listOf(literal, "08ee00000002010a0003"), link.writes.takeLast(2), preset)
            assertFalse(pending.isCompleted, preset)
            assertEquals(before, reading(session, DeviceField.EQ), preset)
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), preset)
            assertEquals(FieldValue.Text(preset), reading(session, DeviceField.EQ), preset)
        }
        session.disconnect()
    }

    @Test
    fun allFiveI7PresetsShareTheSessionSequenceAndNormalizeBothEqReplyLayouts() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        assertEquals(persistentSetOf("pop", "hifi", "rock", "light", "eap"), session.state.value.device?.capabilities?.equalizer?.presets)
        // Independently worked seq04..0d after four complete initialization queries.
        // Single-byte 00/01 are HIFI/POP; the two-byte 00/01 records reverse that order.
        for ((preset, write, query, reply) in listOf(
            EqVector("hifi", "ff04022a002faa", "ff0502fa2a2aaa", "dd00032a01000baa"),
            EqVector("pop", "ff06022a0132aa", "ff0702fa2a2caa", "dd00032a00000aaa"),
            EqVector("rock", "ff08022a0235aa", "ff0902fa2a2eaa", "dd00022a020baa"),
            EqVector("light", "ff0a022a0338aa", "ff0b02fa2a30aa", "dd00022a030caa"),
            EqVector("eap", "ff0c022a063daa", "ff0d02fa2a32aa", "dd00022a060faa"),
        )) {
            val before = reading(session, DeviceField.EQ)
            val pending = async { session.set(DeviceSetting.EQ, FieldValue.Text(preset)) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2), preset)
            assertFalse(pending.isCompleted, preset)
            assertEquals(before, reading(session, DeviceField.EQ), preset)
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), preset)
            assertEquals(FieldValue.Text(preset), reading(session, DeviceField.EQ), preset)
        }
        session.disconnect()
    }

    @Test
    fun i7GameOnAndOffUseDirectWirePolarityAndAlwaysReadTheDevice() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        for ((enabled, write, query, reply) in listOf(
            GameVector(true, "ff04020e0114aa", "ff0502fa0e0eaa", "dd00020e01eeaa"),
            GameVector(false, "ff06020e0015aa", "ff0702fa0e10aa", "dd00020e00edaa"),
        )) {
            val before = reading(session, DeviceField.GAME_MODE)
            val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(enabled)) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            assertEquals(before, reading(session, DeviceField.GAME_MODE))
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Toggle(enabled), reading(session, DeviceField.GAME_MODE))
        }
        session.disconnect()
    }

    @Test
    fun eapAdmissionUsesFirmware151152AndUnsignedFullByteComparison() = runTest {
        for ((firmware, allowed) in listOf(
            null to false,
            "dd00040d010501f5aa" to false, // 151
            "dd00040d010502f6aa" to true, // 152
            "dd00040d001000feaa" to true, // Full unsigned bytes [0,16,0] concatenate to integer 160.
        )) {
            val (session, link) = ready(ModelProfile.I7_RS_V2, firmware)
            assertEquals(allowed, "eap" in requireNotNull(session.state.value.device?.capabilities?.equalizer).presets, firmware)
            if (allowed) {
                val pending = async { session.set(DeviceSetting.EQ, FieldValue.Text("eap")) }
                runCurrent()
                assertEquals(listOf("ff04022a0635aa", "ff0502fa2a2aaa"), link.writes.drop(4), firmware)
                assertFalse(pending.isCompleted)
                link.incoming.send("dd00022a060faa".bytes())
                runCurrent()
                assertEquals(OperationResult.Success, pending.await(), firmware)
            } else {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.EQ, FieldValue.Text("eap"))).error.code, firmware)
                assertEquals(4, link.writes.size, firmware)
                assertTrue(session.state.value.settingOperations.isEmpty())
            }
            session.disconnect()
        }
    }

    @Test
    fun observedFirmwareChangesRefreshEapCapabilityWithoutInventingADeviceEq() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2, firmwareReply = null)
        assertEquals(persistentSetOf("pop", "hifi", "rock", "light"), session.state.value.device?.capabilities?.equalizer?.presets)
        link.incoming.send("dd00040d010501f5aa".bytes())
        runCurrent()
        assertFalse("eap" in requireNotNull(session.state.value.device?.capabilities?.equalizer).presets)
        link.incoming.send("dd00040d010502f6aa".bytes())
        runCurrent()
        assertTrue("eap" in requireNotNull(session.state.value.device?.capabilities?.equalizer).presets)
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        link.incoming.send("dd00040d010501f5aa".bytes())
        runCurrent()
        assertFalse("eap" in requireNotNull(session.state.value.device?.capabilities?.equalizer).presets)
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.EQ, FieldValue.Text("eap"))).error.code)
        assertEquals(4, link.writes.size)
        session.disconnect()
    }

    @Test
    fun incorrectEqOrGameTypesAndUnsupportedPresetsProduceNoWireTraffic() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val count = link.writes.size
            for ((setting, value) in listOf(
                DeviceSetting.EQ to FieldValue.Number(0),
                DeviceSetting.EQ to FieldValue.Toggle(true),
                DeviceSetting.GAME_MODE to FieldValue.Text("on"),
                DeviceSetting.GAME_MODE to FieldValue.Number(1),
            )) {
                assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(setting, value)).error.code, profile.name)
            }
            for (preset in listOf("custom", "custom_slot", "game1", "missing")) {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.EQ, FieldValue.Text(preset))).error.code, profile.name)
            }
            if (profile == ModelProfile.I5_ZT) {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.EQ, FieldValue.Text("eap"))).error.code)
            }
            assertEquals(count, link.writes.size, profile.name)
            assertTrue(session.state.value.settingOperations.isEmpty())
            session.disconnect()
        }
    }

    @Test
    fun queuedEapRechecksFirmwareBeforeSendingAndSharesTheGameSequence() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        val game = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(true)) }
        runCurrent()
        val eap = async { session.set(DeviceSetting.EQ, FieldValue.Text("eap")) }
        runCurrent()
        assertEquals(listOf(SettingStage.WRITTEN, SettingStage.SUBMITTED), session.state.value.settingOperations.map { it.stage })
        assertEquals(6, link.writes.size)
        link.incoming.send("dd00040d010501f5aa".bytes())
        runCurrent()
        assertEquals(6, link.writes.size)
        link.incoming.send("dd00020e01eeaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, game.await())
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(eap.await()).error.code)
        assertEquals(6, link.writes.size)
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        val pop = async { session.set(DeviceSetting.EQ, FieldValue.Text("pop")) }
        runCurrent()
        assertEquals(listOf("ff06022a0132aa", "ff0702fa2a2caa"), link.writes.takeLast(2))
        link.incoming.send("dd00022a010aaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pop.await())
        session.disconnect()
    }

    @Test
    fun cachedEqualizerValueWithoutFreshReadbackTimesOutAndEndsQueuedGameSettings() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val initialCount = link.writes.size
            val eq = async { session.set(DeviceSetting.EQ, FieldValue.Text("hifi")) }
            runCurrent()
            val game = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(true)) }
            runCurrent()
            assertFalse(eq.isCompleted)
            assertFalse(game.isCompleted)
            assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
            advanceTimeBy(51)
            runCurrent()
            assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(eq.await()).error.code, profile.name)
            assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(game.await()).error.code, profile.name)
            assertEquals(ConnectionState.Disconnected, session.state.value.connection)
            assertEquals(listOf(SettingStage.TIMED_OUT, SettingStage.DISCONNECTED), session.state.value.settingOperations.map { it.stage })
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(initialCount + 2, link.writes.size)
        }
    }

    private data class EqVector(val preset: String, val write: String, val query: String, val reply: String)
    private data class GameVector(val enabled: Boolean, val write: String, val query: String, val reply: String)

    private suspend fun TestScope.ready(profile: ModelProfile, firmwareReply: String? = "dd00040d010502f6aa"): Pair<ControlSession, EqualizerLink> {
        val link = EqualizerLink(profile, firmwareReply)
        val transport = EqualizerTransport(link)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
        )
        session.startScan()
        val advertisement = if (profile == ModelProfile.I5_ZT) {
            Advertisement(DeviceId("i5"), manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1)))
        } else {
            Advertisement(DeviceId("i7"), manufacturerData = mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f)))
        }
        transport.advertisements.emit(advertisement)
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        link.initializing = false
        return session to link
    }

    private class EqualizerTransport(val link: EqualizerLink) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = link
    }

    private class EqualizerLink(val profile: ModelProfile, val firmwareReply: String?) : ControlConnection {
        override val maxWriteSize = 20
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        var initializing = true
        val writes = mutableListOf<String>()
        override suspend fun subscribe() = Unit
        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.hex()
            writes += hex
            if (initializing) {
                if (profile == ModelProfile.I5_ZT) {
                    i5Replies[hex.substring(10, 14)]?.let { incoming.send(it.bytes()) }
                } else {
                    // Synthetic i7 mode + game off + single-byte HIFI, then optional firmware.
                    incoming.send("dd00020901e9aadd00020e00edaadd00022a0009aa".bytes())
                    firmwareReply?.let { incoming.send(it.bytes()) }
                }
            }
        }
        override suspend fun close() {
            incoming.close()
        }
    }

    companion object {
        private fun reading(session: ControlSession, field: DeviceField) = assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(field)).value
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Independent i5 Linux read capture; setting confirmations below are synthetic.
        private val i5Replies = mapOf(
            "0101" to "09ff00000101011100000164620000ffe2", "0602" to "09ff00000106020e000000010020",
            "0201" to "09ff00000102010b000118", "0603" to "09ff00000106030b00011e",
            "0801" to "09ff00000108010b00011e", "0d01" to "09ff0000010d010e00010205002d",
            "0401" to "09ff00000104011200060502040605030746", "0a01" to "09ff0000010a010b00001f",
            "0605" to "09ff00000106050e000101010126", "0c01" to "09ff0000010c010b000122",
            "0607" to "09ff00000106070b000223", "0604" to "09ff00000106040b00001e",
            "0e03" to "09ff0000010e030b000126",
        )
    }
}
