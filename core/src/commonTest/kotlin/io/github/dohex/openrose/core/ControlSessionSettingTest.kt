package io.github.dohex.openrose.core

import kotlinx.collections.immutable.persistentSetOf
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope

/** Public session + literal transport vectors, never a replacement codec or confirmation path. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionSettingTest {
    @Test
    fun i5SettingWritesThenReadsBeforeConfirmingActualState() = runTest {
        val link = SettingLink()
        val transport = SettingTransport(link)
        val session = ControlSession(PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport, scope = backgroundScope, timing = SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = 0))
        session.startScan()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        link.initializing = false
        val setting = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()

        assertEquals(listOf("08ee00000006820e00000100008d", "08ee00000006020a0008"), link.writes.drop(13))
        assertFalse(setting.isCompleted)
        assertEquals(FieldValue.Text("wind"), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value)
        // Synthetic known normal tail from the independent reverse-engineering receiver table.
        link.incoming.send("09ff00000106020e000001000020".bytes())
        runCurrent()

        assertEquals(OperationResult.Success, setting.await())
        assertEquals(FieldValue.Text("normal"), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value)
        session.disconnect()
    }

    @Test
    fun aFrameLargerThanTheLinkCapacityNeverReachesTheDevice() = runTest {
        val (session, link) = ready(maxWriteSize = 10)

        val result = session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal"))

        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(13, link.writes.size)
        assertEquals(SettingStage.FAILED, session.state.value.settingOperations.last().stage)
        session.disconnect()
    }

    @Test
    fun uncertainTransportWriteTimeoutClosesTheLinkAndNeverStartsQueuedSettings() = runTest {
        val (session, link) = ready()
        link.writeGate = CompletableDeferred()
        val first = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        runCurrent()
        val second = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(first.await()).error.code)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(second.await()).error.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(listOf("08ee00000006820e00010000008d"), link.writes.drop(13))
        assertEquals(listOf(SettingStage.TIMED_OUT, SettingStage.DISCONNECTED), session.state.value.settingOperations.map { it.stage })
        link.writeGate?.complete(Unit)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(14, link.writes.size)
    }

    @Test
    fun submittedAndWrittenAreObservableBeforeDeviceConfirmation() = runTest {
        val (session, link) = ready()
        link.writeGate = CompletableDeferred()
        val pending = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        assertEquals(SettingStage.SUBMITTED, session.state.value.settingOperations.single().stage)
        assertFalse(pending.isCompleted)
        link.writeGate?.complete(Unit)
        runCurrent()
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.single().stage)
        assertFalse(pending.isCompleted)
        link.incoming.send("09ff00000106020e000001000020".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        assertEquals(SettingStage.CONFIRMED, session.state.value.settingOperations.single().stage)
        session.disconnect()
    }

    @Test
    fun cachedMatchingValueCannotConfirmAndUnknownFreshValueTimesOut() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("wind")) }
        runCurrent()
        assertFalse(pending.isCompleted)
        link.incoming.send("09ff00000106020e000000000000".bytes())
        runCurrent()
        assertFalse(pending.isCompleted)
        assertIs<FieldReading.Unknown>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE))
        advanceTimeBy(51)
        runCurrent()
        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun differentFreshDeviceStateIsNotAppliedAndRemainsAuthoritative() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        runCurrent()
        link.incoming.send("09ff00000106020e000001000020".bytes())
        runCurrent()
        assertEquals(ControlErrorCode.NOT_APPLIED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(SettingStage.NOT_APPLIED, session.state.value.settingOperations.single().stage)
        assertEquals(FieldValue.Text("normal"), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value)
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        session.disconnect()
    }

    @Test
    fun invalidValuesAndUnsupportedModelModesNeverProduceWireTraffic() = runTest {
        val (session, link) = ready()
        for ((setting, value) in listOf(
            DeviceSetting.ANC_LEVEL to FieldValue.Number(2),
            DeviceSetting.TRANSPARENCY_LEVEL to FieldValue.Number(6),
            DeviceSetting.NOISE_MODE to FieldValue.Toggle(true),
            DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(mapOf("anc" to true)),
            DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(mapOf("normal" to true, "transparency" to null, "wind" to false, "anc" to true)),
        )) assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(setting, value)).error.code)
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("auto"))).error.code)
        assertEquals(13, link.writes.size)
        assertTrue(session.state.value.settingOperations.isEmpty())
        assertEquals(persistentSetOf("normal", "transparency", "wind", "anc"), session.state.value.device?.capabilities?.noiseControl?.modes)
        session.disconnect()
    }

    @Test
    fun i5NoiseLevelWritesRemainIndependentOfCurrentModeAndOtherConfirmedLevel() = runTest {
        val (session, link) = ready()
        assertEquals(
            FieldValue.Text("wind"),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value,
        )

        val anc = async { session.set(DeviceSetting.ANC_LEVEL, FieldValue.Number(3)) }
        runCurrent()
        assertEquals(
            listOf("08ee00000006870b00018f", "08ee00000006070a000d"),
            link.writes.drop(13),
        )
        link.incoming.send("09ff00000106070b000122".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, anc.await())
        assertEquals(
            FieldValue.Text("wind"),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value,
        )
        assertEquals(
            FieldValue.Number(3),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.ANC_LEVEL)).value,
        )
        assertEquals(
            FieldValue.Number(1),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.TRANSPARENCY_LEVEL)).value,
        )

        val transparency = async { session.set(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(3)) }
        runCurrent()
        assertEquals(
            listOf(
                "08ee00000006870b00018f", "08ee00000006070a000d",
                "08ee00000006840b00018c", "08ee00000006040a000a",
            ),
            link.writes.drop(13),
        )
        link.incoming.send("09ff00000106040b00011f".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, transparency.await())
        assertEquals(
            FieldValue.Text("wind"),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value,
        )
        assertEquals(
            FieldValue.Number(3),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.ANC_LEVEL)).value,
        )
        assertEquals(
            FieldValue.Number(3),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.TRANSPARENCY_LEVEL)).value,
        )
        session.disconnect()
    }

    @Test
    fun simultaneousSettingsStayFifoEvenWhenOneCallerCancels() = runTest {
        val (session, link) = ready()
        val first = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        runCurrent()
        val second = async { session.set(DeviceSetting.ANC_LEVEL, FieldValue.Number(5)) }
        runCurrent()
        assertEquals(listOf(SettingStage.WRITTEN, SettingStage.SUBMITTED), session.state.value.settingOperations.map { it.stage })
        assertEquals(15, link.writes.size)
        first.cancelAndJoin()
        runCurrent()
        assertEquals(15, link.writes.size)
        link.incoming.send("09ff00000106020e000100000020".bytes())
        runCurrent()
        assertEquals(listOf("08ee00000006820e00010000008d", "08ee00000006020a0008", "08ee00000006870b000290", "08ee00000006070a000d"), link.writes.drop(13))
        assertFalse(second.isCompleted)
        link.incoming.send("09ff00000106070b000223".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, second.await())
        assertEquals(listOf(SettingStage.CONFIRMED, SettingStage.CONFIRMED), session.state.value.settingOperations.map { it.stage })
        session.disconnect()
    }

    @Test
    fun disconnectEndsBothActiveAndQueuedSettingsAndReconnectNeverReplays() = runTest {
        val (session, link) = ready()
        val first = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        runCurrent()
        val second = async { session.set(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(3)) }
        runCurrent()
        session.disconnect()
        runCurrent()
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(first.await()).error.code)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(second.await()).error.code)
        assertEquals(listOf(SettingStage.DISCONNECTED, SettingStage.DISCONNECTED), session.state.value.settingOperations.map { it.stage })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(15, link.writes.size)
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal"))).error.code)
    }

    @Test
    fun allSettingAndReadbackWritesKeepTheInitializationPacing() = runTest {
        val (session, link) = ready(interval = 100)
        val pending = async { session.set(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(3)) }
        advanceTimeBy(201)
        runCurrent()
        assertEquals(listOf("08ee00000006840b00018c", "08ee00000006040a000a"), link.writes.drop(13))
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.single().stage)
        assertTrue(link.writeTimes.zipWithNext().all { (a, b) -> b - a >= 100 })
        link.incoming.send("09ff00000106040b00011f".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        session.disconnect()
    }

    @Test
    fun mutableCallerCycleOptionsAreSnapshottedBeforeTheyWaitInTheQueue() = runTest {
        val (session, link) = ready()
        val first = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        runCurrent()
        val flags = mutableMapOf<String, Boolean?>("normal" to true, "transparency" to false, "wind" to false, "anc" to true)
        val second = async { session.set(DeviceSetting.NOISE_CYCLE, FieldValue.NoiseCycle(flags)) }
        runCurrent()
        flags["anc"] = false
        link.incoming.send("09ff00000106020e000100000020".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, first.await())
        assertEquals(listOf("08ee00000006850e000100000191", "08ee00000006050a000b"), link.writes.takeLast(2))
        assertEquals(true, assertIs<FieldValue.NoiseCycle>(session.state.value.settingOperations.last().requestedValue).flags["anc"])
        link.incoming.send("09ff00000106050e000100000124".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, second.await())
        session.disconnect()
    }

    @Test
    fun timeoutAndLateOldReplyCannotChangeAReconnectedSession() = runTest {
        val (session, old, transport) = ready()
        val pending = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        val fresh = SettingLink()
        transport.link = fresh
        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        fresh.initializing = false
        val before = session.state.value
        old.deliverLate("09ff00000106020e000100000020")
        runCurrent()
        assertEquals(before, session.state.value)
        assertEquals(13, fresh.writes.size)
        assertEquals(15, old.writes.size)
        session.disconnect()
    }

    @Test
    fun i7UsesTheSameSequenceAcrossInitializationAutomaticModeAndExitReadback() = runTest {
        val (session, link) = ready(profile = ModelProfile.I7_RS_V2)
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("deep"))).error.code)
        assertEquals(persistentSetOf("normal", "transparency", "wind", "anc", "auto", "dynamic"), session.state.value.device?.capabilities?.noiseControl?.modes)
        val first = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("dynamic")) }
        runCurrent()
        // Independently worked sender sequence04/05 after four capacity20 initialization reads.
        assertEquals(listOf("ff0402090715aa", "ff0502fa0909aa"), link.writes.drop(4))
        assertFalse(first.isCompleted)
        assertEquals(FieldValue.Text("auto"), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value)
        // All i7 RX in this suite is synthetic, per recovered DD/LTV receiver rules.
        link.incoming.send("dd00020907efaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, first.await())
        val second = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        assertEquals(listOf("ff0602090212aa", "ff0702fa090baa"), link.writes.takeLast(2))
        // A vendor automatic-mode exit can report wind; retain the actual result.
        link.incoming.send("dd00020904ecaa".bytes())
        runCurrent()
        assertEquals(ControlErrorCode.NOT_APPLIED, assertIs<OperationResult.Failure>(second.await()).error.code)
        assertEquals(FieldValue.Text("wind"), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value)
        session.disconnect()
    }

    @Test
    fun i7LevelsUseDirectWireValuesAndCycleOrderIsModelSpecific() = runTest {
        val (session, link) = ready(profile = ModelProfile.I7_RS_V2)
        assertEquals(
            FieldValue.Text("auto"),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value,
        )
        val level = async { session.set(DeviceSetting.ANC_LEVEL, FieldValue.Number(3)) }
        runCurrent()
        assertEquals(listOf("ff04022c0334aa", "ff0502fa2c2caa"), link.writes.drop(4))
        link.incoming.send("dd00022c030eaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, level.await())
        assertEquals(
            FieldValue.Text("auto"),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value,
            "The independent ANC level readback must not replace the current automatic mode",
        )
        assertEquals(
            FieldValue.Number(3),
            assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.ANC_LEVEL)).value,
        )
        val cycle = async { session.set(DeviceSetting.NOISE_CYCLE, FieldValue.NoiseCycle(mapOf("normal" to true, "transparency" to true, "wind" to false, "anc" to true))) }
        runCurrent()
        assertEquals(listOf("ff0605360100010143aa", "ff0702fa3638aa"), link.writes.takeLast(2))
        link.incoming.send("dd000536010001011baa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, cycle.await())
        session.disconnect()
    }

    @Test
    fun setterEchoCannotReplaceTheMandatoryFreshReadback() = runTest {
        val (session, link) = ready()
        link.onWrite = { hex ->
            if (hex == "08ee00000006820e00000100008d") {
                // Synthetic high-op echo: no device rejection or acknowledgment semantics inferred.
                link.incoming.send("09ff00000106820e000001000020".bytes())
            }
        }
        val pending = async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        assertEquals(listOf("08ee00000006820e00000100008d", "08ee00000006020a0008"), link.writes.drop(13))
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.single().stage)
        assertEquals(FieldValue.Text("wind"), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value)
        assertFalse(pending.isCompleted)
        link.incoming.send("09ff00000106020e000001000020".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        session.disconnect()
    }

    @Test
    fun publicQueueHasABoundAndDisconnectCompletesEveryAcceptedCaller() = runTest {
        val (session, link) = ready()
        val pending = List(33) { async { session.set(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) } }
        runCurrent()
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(pending.last().await()).error.code)
        assertEquals(32, session.state.value.settingOperations.size)
        assertEquals(15, link.writes.size)
        session.disconnect()
        runCurrent()
        pending.dropLast(1).forEach { assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(it.await()).error.code) }
        assertTrue(session.state.value.settingOperations.all { it.stage == SettingStage.DISCONNECTED })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(15, link.writes.size)
    }

    @Test
    fun i5PartiallyUnknownCycleReadbackWaitsForCompleteDeviceState() = runTest { partiallyUnknownCycleReadbackWaitsForCompleteDeviceState(ModelProfile.I5_ZT) }

    @Test
    fun i7PartiallyUnknownCycleReadbackWaitsForCompleteDeviceState() = runTest { partiallyUnknownCycleReadbackWaitsForCompleteDeviceState(ModelProfile.I7_RS_V2) }

    private suspend fun TestScope.partiallyUnknownCycleReadbackWaitsForCompleteDeviceState(profile: ModelProfile) {
        val (session, link) = ready(profile = profile)
        val pending = async { session.set(DeviceSetting.NOISE_CYCLE, requestedCycle) }
        runCurrent()
        link.incoming.send(partialCycleReply(profile).bytes())
        runCurrent()

        val actual = assertIs<FieldValue.NoiseCycle>(assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_CYCLE)).value)
        assertEquals(null, actual.flags["transparency"], profile.name)
        assertFalse(pending.isCompleted, profile.name)
        assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.single().stage, profile.name)

        link.incoming.send(completeCycleReply(profile).bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pending.await(), profile.name)
        assertEquals(SettingStage.CONFIRMED, session.state.value.settingOperations.single().stage, profile.name)
        assertEquals(requestedCycle, assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.NOISE_CYCLE)).value, profile.name)
        session.disconnect()
    }

    @Test
    fun i5OnlyPartiallyUnknownCycleReadbackTimesOut() = runTest { onlyPartiallyUnknownCycleReadbackTimesOut(ModelProfile.I5_ZT) }

    @Test
    fun i7OnlyPartiallyUnknownCycleReadbackTimesOut() = runTest { onlyPartiallyUnknownCycleReadbackTimesOut(ModelProfile.I7_RS_V2) }

    private suspend fun TestScope.onlyPartiallyUnknownCycleReadbackTimesOut(profile: ModelProfile) {
        val (session, link) = ready(profile = profile)
        val pending = async { session.set(DeviceSetting.NOISE_CYCLE, requestedCycle) }
        runCurrent()
        link.incoming.send(partialCycleReply(profile).bytes())
        runCurrent()
        advanceTimeBy(51)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code, profile.name)
        assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage, profile.name)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection, profile.name)
    }

    @Test
    fun i5CompleteDifferentCycleReadbackIsNotApplied() = runTest { completeDifferentCycleReadbackIsNotApplied(ModelProfile.I5_ZT) }

    @Test
    fun i7CompleteDifferentCycleReadbackIsNotApplied() = runTest { completeDifferentCycleReadbackIsNotApplied(ModelProfile.I7_RS_V2) }

    private suspend fun TestScope.completeDifferentCycleReadbackIsNotApplied(profile: ModelProfile) {
        val (session, link) = ready(profile = profile)
        val pending = async { session.set(DeviceSetting.NOISE_CYCLE, requestedCycle) }
        runCurrent()
        // Synthetic complete all-off replies, independently worked from each model's RX layout.
        link.incoming.send((if (profile == ModelProfile.I5_ZT) "09ff00000106050e000000000022" else "dd0005360000000018aa").bytes())
        runCurrent()

        assertEquals(ControlErrorCode.NOT_APPLIED, assertIs<OperationResult.Failure>(pending.await()).error.code, profile.name)
        assertEquals(SettingStage.NOT_APPLIED, session.state.value.settingOperations.single().stage, profile.name)
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        session.disconnect()
    }

    private suspend fun TestScope.ready(maxWriteSize: Int = 20, interval: Long = 0, profile: ModelProfile = ModelProfile.I5_ZT): Triple<ControlSession, SettingLink, SettingTransport> {
        val link = SettingLink(maxWriteSize, now = { testScheduler.currentTime }, profile = profile)
        val transport = SettingTransport(link)
        val session = ControlSession(PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport, scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = interval, i7WriteIntervalMillis = interval))
        session.startScan()
        val advertisement = if (profile == ModelProfile.I5_ZT) i5Advertisement else i7Advertisement
        transport.advertisements.emit(advertisement)
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        link.initializing = false
        return Triple(session, link, transport)
    }

    private class SettingTransport(var link: SettingLink) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = link
    }

    private class SettingLink(override val maxWriteSize: Int = 20, val now: () -> Long = { 0L }, val profile: ModelProfile = ModelProfile.I5_ZT) : ControlConnection {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var collector: FlowCollector<ByteArray>? = null
        override val notifications: Flow<ByteArray> = object : Flow<ByteArray> {
            override suspend fun collect(collector: FlowCollector<ByteArray>) {
                this@SettingLink.collector = collector
                incoming.receiveAsFlow().collect(collector)
            }
        }
        suspend fun deliverLate(hex: String) { requireNotNull(collector).emit(hex.bytes()) }
        var initializing = true
        val writes = mutableListOf<String>()
        val writeTimes = mutableListOf<Long>()
        var writeGate: CompletableDeferred<Unit>? = null
        var onWrite: suspend (String) -> Unit = {}
        override suspend fun subscribe() = Unit
        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.hex()
            writes += hex
            writeTimes += now()
            writeGate?.await()
            onWrite(hex)
            if (initializing) {
                if (profile == ModelProfile.I5_ZT) i5Replies[hex.substring(10, 14)]?.let { incoming.send(it.bytes()) }
                else incoming.send("dd00020905edaadd00040d010502f6aa".bytes())
            }
        }
        override suspend fun close() { incoming.close() }
    }

    companion object {
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private val requestedCycle = FieldValue.NoiseCycle(mapOf("normal" to true, "transparency" to false, "wind" to false, "anc" to true))
        // Synthetic replies preserve i5 normal/transparency/wind/ANC vs i7 ANC/wind/normal/transparency.
        private fun partialCycleReply(profile: ModelProfile) = if (profile == ModelProfile.I5_ZT) "09ff00000106050e0001ff000123" else "dd000536010001ff19aa"
        private fun completeCycleReply(profile: ModelProfile) = if (profile == ModelProfile.I5_ZT) "09ff00000106050e000100000124" else "dd000536010001001aaa"
        private val i5Advertisement = Advertisement(DeviceId("i5"), manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1)))
        private val i7Advertisement = Advertisement(DeviceId("i7"), manufacturerData = mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f)))
        // Independent real Linux RX capture; no setting hardware capture exists.
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
