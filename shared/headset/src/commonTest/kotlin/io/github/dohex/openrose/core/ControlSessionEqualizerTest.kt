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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionEqualizerTest {
    @Test
    fun i5GameOnAndOffUseTheOppositeWirePolarityAndWaitForReadback() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        for ((enabled, literal, reply) in listOf(
            Triple(true, "08ee00000006830b00008a", "09ff00000106030b00001d"),
            Triple(false, "08ee00000006830b00018b", "09ff00000106030b00011e"),
        )) {
            val before = reading(session, DeviceField.GAME_MODE)
            val pending = async { session.set(HeadsetCommand.GameMode(enabled)) }
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
        for ((preset, literal, reply) in listOf(
            Triple("pop", "08ee00000002810b000084", "09ff00000102010b000017"),
            Triple("hifi", "08ee00000002810b000185", "09ff00000102010b000118"),
            Triple("rock", "08ee00000002810b000286", "09ff00000102010b000219"),
            Triple("light", "08ee00000002810b000387", "09ff00000102010b00031a"),
        )) {
            val before = reading(session, DeviceField.EQ)
            val pending = async { session.set(HeadsetCommand.EqualizerPreset(preset)) }
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
        for (vector in listOf(
            EqVector("hifi", "ff03022a002eaa", "ff0402fa2a29aa", "dd00032a01000baa"),
            EqVector("pop", "ff05022a0131aa", "ff0602fa2a2baa", "dd00032a00000aaa"),
            EqVector("rock", "ff07022a0234aa", "ff0802fa2a2daa", "dd00022a020baa"),
            EqVector("light", "ff09022a0337aa", "ff0a02fa2a2faa", "dd00022a030caa"),
            EqVector("eap", "ff0b022a063caa", "ff0c02fa2a31aa", "dd00022a060faa"),
        )) {
            val before = reading(session, DeviceField.EQ)
            val pending = async { session.set(HeadsetCommand.EqualizerPreset(vector.preset)) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2), vector.preset)
            assertFalse(pending.isCompleted, vector.preset)
            assertEquals(before, reading(session, DeviceField.EQ), vector.preset)
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), vector.preset)
            assertEquals(FieldValue.Text(vector.preset), reading(session, DeviceField.EQ), vector.preset)
        }
        session.disconnect()
    }

    @Test
    fun i7GameOnAndOffUseDirectWirePolarityAndAlwaysReadTheDevice() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        for (vector in listOf(
            GameVector(true, "ff03020e0113aa", "ff0402fa0e0daa", "dd00020e01eeaa"),
            GameVector(false, "ff05020e0014aa", "ff0602fa0e0faa", "dd00020e00edaa"),
        )) {
            val before = reading(session, DeviceField.GAME_MODE)
            val pending = async { session.set(HeadsetCommand.GameMode(vector.enabled)) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            assertEquals(before, reading(session, DeviceField.GAME_MODE))
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Toggle(vector.enabled), reading(session, DeviceField.GAME_MODE))
        }
        session.disconnect()
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
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.EqualizerPreset("eap"))).error.code)
        assertEquals(3, link.writes.size)
        session.disconnect()
    }

    @Test
    fun unsupportedPresetsProduceNoWireTraffic() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val count = link.writes.size
            for (preset in listOf("custom", "custom_slot", "game1", "missing")) {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.EqualizerPreset(preset))).error.code, profile.name)
            }
            if (profile == ModelProfile.I5_ZT) {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.EqualizerPreset("eap"))).error.code)
            }
            assertEquals(count, link.writes.size, profile.name)
            assertTrue(session.state.value.settingOperations.isEmpty())
            session.disconnect()
        }
    }

    @Test
    fun queuedEapRechecksFirmwareBeforeSendingAndSharesTheGameSequence() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        val game = async { session.set(HeadsetCommand.GameMode(true)) }
        runCurrent()
        val eap = async { session.set(HeadsetCommand.EqualizerPreset("eap")) }
        runCurrent()
        assertEquals(listOf(SettingStage.WRITTEN, SettingStage.SUBMITTED), session.state.value.settingOperations.map { it.stage })
        assertEquals(5, link.writes.size)
        link.incoming.send("dd00040d010501f5aa".bytes())
        runCurrent()
        assertEquals(5, link.writes.size)
        link.incoming.send("dd00020e01eeaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, game.await())
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(eap.await()).error.code)
        assertEquals(5, link.writes.size)
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        val pop = async { session.set(HeadsetCommand.EqualizerPreset("pop")) }
        runCurrent()
        assertEquals(listOf("ff05022a0131aa", "ff0602fa2a2baa"), link.writes.takeLast(2))
        link.incoming.send("dd00022a010aaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pop.await())
        session.disconnect()
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
        private fun reading(session: ControlSession, field: DeviceField) = assertIs<FieldReading.Known>(session.state.value.device?.diagnostics?.fields?.get(field)).value
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
