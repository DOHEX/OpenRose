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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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

/** Public control session; independent fixed TX vectors and synthetic DD/LTV device RX. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionAdvancedAudioTest {
    @Test
    fun customCurveUsesTwoWritesThenTwoIndependentFreshReadbacksAtCapacity16() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()
        assertEquals(listOf("ff04022a0433aa", "ff050b3e86838100010203040506ecaa", "ff0602fa2a2baa"), link.writes.drop(4))
        assertFalse(pending.isCompleted)
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        assertEquals(2, session.state.value.settingOperations.single().writtenFrames)
        assertEquals(2, session.state.value.settingOperations.single().totalFrames)
        assertTrue(session.state.value.settingOperations.single().confirmedFields.isEmpty())
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals("ff0702fa3e40aa", link.writes.last())
        assertEquals(setOf(DeviceField.EQ), session.state.value.settingOperations.single().confirmedFields)
        assertFalse(pending.isCompleted)
        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        assertEquals(setOf(DeviceField.EQ, DeviceField.CUSTOM_EQ), session.state.value.settingOperations.single().confirmedFields)
        assertEquals(SettingStage.CONFIRMED, session.state.value.settingOperations.single().stage)
        assertEquals(curve, reading(session, DeviceField.CUSTOM_EQ))
        session.disconnect()
    }

    @Test
    fun customCurveRejectsInsufficientCapacityBeforeSendingAnyPartOfThePlan() = runTest {
        val (session, link) = ready(capacity = 15)
        val before = link.writes.size

        val result = session.set(DeviceSetting.CUSTOM_EQ, curve)

        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(before, link.writes.size)
        val operation = session.state.value.settingOperations.single()
        assertEquals(0, operation.writtenFrames)
        assertEquals(2, operation.totalFrames)
        assertEquals(SettingStage.FAILED, operation.stage)
        session.disconnect()
    }

    @Test
    fun customCurveReportsOnlyCompletedWritesWhenEitherWriteFails() = runTest {
        for (failedWrite in 1..2) {
            val (session, link) = ready()
            val before = link.writes.size
            link.failAt = before + failedWrite
            val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
            runCurrent()

            assertEquals(ControlErrorCode.FAILED, assertIs<OperationResult.Failure>(pending.await()).error.code)
            assertEquals(failedWrite, link.writes.size - before)
            val operation = session.state.value.settingOperations.single()
            assertEquals(failedWrite - 1, operation.writtenFrames)
            assertEquals(2, operation.totalFrames)
            assertEquals(SettingStage.FAILED, operation.stage)
            assertEquals(listOf("ff04022a0433aa", "ff050b3e86838100010203040506ecaa").take(failedWrite), link.writes.drop(before))
            assertEquals(ConnectionState.Disconnected, session.state.value.connection)
            assertEquals(null, session.state.value.device)
        }
    }

    @Test
    fun inFlightSecondWriteShowsPartialProgressAndDisconnectKeepsIt() = runTest {
        val (session, link) = ready()
        val before = link.writes.size
        link.gateAt = before + 2
        val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()

        val inProgress = session.state.value.settingOperations.single()
        assertEquals(SettingStage.PARTIALLY_WRITTEN, inProgress.stage)
        assertEquals(1, inProgress.writtenFrames)
        assertEquals(2, inProgress.totalFrames)
        assertEquals(listOf("ff04022a0433aa", "ff050b3e86838100010203040506ecaa"), link.writes.drop(before))

        session.disconnect()
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        val disconnected = session.state.value.settingOperations.single()
        assertEquals(SettingStage.DISCONNECTED, disconnected.stage)
        assertEquals(1, disconnected.writtenFrames)
        assertEquals(2, disconnected.totalFrames)
        assertTrue(disconnected.confirmedFields.isEmpty())
    }

    @Test
    fun knownCustomSlotModeStillReadsCurveAndReportsTheActualMismatch() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()

        link.incoming.send("dd00022a050eaa".bytes())
        runCurrent()
        assertEquals("ff0702fa3e40aa", link.writes.last())
        assertEquals(FieldValue.Text("custom_slot"), reading(session, DeviceField.EQ))
        assertTrue(session.state.value.settingOperations.single().confirmedFields.isEmpty())

        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()

        assertEquals(ControlErrorCode.NOT_APPLIED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        val operation = session.state.value.settingOperations.single()
        assertEquals(setOf(DeviceField.CUSTOM_EQ), operation.confirmedFields)
        assertEquals(SettingStage.NOT_APPLIED, operation.stage)
        session.disconnect()
    }

    @Test
    fun curveNotificationBeforeItsReadbackCannotConfirmTheSecondField() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()

        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()
        assertTrue(session.state.value.settingOperations.single().confirmedFields.isEmpty())
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals("ff0702fa3e40aa", link.writes.last())
        assertEquals(setOf(DeviceField.EQ), session.state.value.settingOperations.single().confirmedFields)

        advanceTimeBy(51)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        val operation = session.state.value.settingOperations.single()
        assertEquals(setOf(DeviceField.EQ), operation.confirmedFields)
        assertEquals(2, operation.writtenFrames)
        assertEquals(SettingStage.TIMED_OUT, operation.stage)
    }

    @Test
    fun missingOrUnknownModeCannotAdvanceToTheCurveReadback() = runTest {
        for (modeReply in listOf("dd00022b000aaa", "dd00022a0710aa")) {
            val (session, link) = ready()
            val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
            runCurrent()

            link.incoming.send(modeReply.bytes())
            runCurrent()
            advanceTimeBy(51)
            runCurrent()

            assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code, modeReply)
            assertEquals("ff0602fa2a2baa", link.writes.last(), modeReply)
            assertEquals(emptySet(), session.state.value.settingOperations.single().confirmedFields, modeReply)
            assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage, modeReply)
        }
    }

    @Test
    fun disconnectAfterModeConfirmationKeepsItsEvidenceAndStopsThePlan() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals("ff0702fa3e40aa", link.writes.last())

        session.disconnect()
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        val operation = session.state.value.settingOperations.single()
        assertEquals(2, operation.writtenFrames)
        assertEquals(2, operation.totalFrames)
        assertEquals(setOf(DeviceField.EQ), operation.confirmedFields)
        assertEquals(SettingStage.DISCONNECTED, operation.stage)
        assertEquals("ff0702fa3e40aa", link.writes.last())
    }

    @Test
    fun modeThatBecomesUnknownBeforeFinalConfirmationTimesOutInsteadOfReportingSuccess() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals(setOf(DeviceField.EQ), session.state.value.settingOperations.single().confirmedFields)

        link.incoming.send("dd00022a0710aa".bytes())
        runCurrent()
        assertIs<FieldReading.Unknown>(session.state.value.device?.fields?.get(DeviceField.EQ))
        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()

        assertEquals(setOf(DeviceField.CUSTOM_EQ), session.state.value.settingOperations.single().confirmedFields)
        assertFalse(pending.isCompleted)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage)
    }

    @Test
    fun queuedCurveOperationsKeepFifoOrder() = runTest {
        val (session, link) = ready()
        val first = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()
        val second = async { session.set(DeviceSetting.CUSTOM_EQ, curve) }
        runCurrent()
        assertEquals(listOf(SettingStage.WRITTEN, SettingStage.SUBMITTED), session.state.value.settingOperations.map { it.stage })

        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()
        assertEquals(OperationResult.Success, first.await())
        val secondStart = link.writes.size - 3
        assertEquals(listOf("ff08022a0437aa", "ff090b3e86838100010203040506f0aa", "ff0a02fa2a2faa"), link.writes.drop(secondStart))

        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()

        assertEquals(OperationResult.Success, second.await())
        assertEquals("ff0b02fa3e44aa", link.writes.last())
        assertEquals(listOf(SettingStage.CONFIRMED, SettingStage.CONFIRMED), session.state.value.settingOperations.map { it.stage })
        session.disconnect()
    }

    @Test
    fun invalidCustomCurvesAreRejectedBeforeAnySessionWrite() = runTest {
        val (session, link) = ready()
        val before = link.writes.size
        for (invalid in listOf(
            FieldValue.IntegerList(emptyList()),
            FieldValue.IntegerList((1..9).toList()),
            FieldValue.IntegerList(listOf(7, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        )) {
            assertEquals(
                ControlErrorCode.INVALID_ARGUMENT,
                assertIs<OperationResult.Failure>(session.set(DeviceSetting.CUSTOM_EQ, invalid)).error.code,
            )
        }
        assertEquals(before, link.writes.size)
        assertTrue(session.state.value.settingOperations.isEmpty())
        session.disconnect()
    }

    @Test
    fun sessionCapabilitiesAndValidationUseTheFirmware213And304Boundaries() = runTest {
        for ((firmware, gainAllowed, muteAllowed) in listOf(
            Triple("dd00040d020102f3aa", false, false),
            Triple(null, false, false),
            Triple("dd00040d020103f4aa", true, false),
            Triple("dd00040d030003f4aa", true, false),
            Triple("dd00040d030004f5aa", true, true),
        )) {
            val (session, link) = ready(firmware = firmware)
            val capabilities = requireNotNull(session.state.value.device).capabilities
            assertEquals(if (gainAllowed) setOf(0, 1, 2) else emptySet(), capabilities.gainLevels, "$firmware gain")
            assertEquals(if (gainAllowed) (0..6).toSet() else emptySet(), capabilities.hearingProtectionLevels, "$firmware protection")
            assertEquals(if (muteAllowed) (0..5).toSet() else (1..5).toSet(), capabilities.promptVolumeLevels, "$firmware prompt")
            val before = link.writes.size
            val gainResult = session.set(DeviceSetting.GAIN, FieldValue.Number(if (gainAllowed) 3 else 0))
            val protectionResult = session.set(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(if (gainAllowed) 7 else 6))
            val promptResult = session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(6))
            assertEquals(
                if (gainAllowed) ControlErrorCode.INVALID_ARGUMENT else ControlErrorCode.UNSUPPORTED,
                assertIs<OperationResult.Failure>(gainResult).error.code,
                "$firmware gain",
            )
            assertEquals(
                if (gainAllowed) ControlErrorCode.INVALID_ARGUMENT else ControlErrorCode.UNSUPPORTED,
                assertIs<OperationResult.Failure>(protectionResult).error.code,
                "$firmware protection",
            )
            assertEquals(
                ControlErrorCode.INVALID_ARGUMENT,
                assertIs<OperationResult.Failure>(promptResult).error.code,
                "$firmware prompt",
            )
            if (!muteAllowed) {
                assertEquals(
                    ControlErrorCode.UNSUPPORTED,
                    assertIs<OperationResult.Failure>(session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(0))).error.code,
                    "$firmware mute",
                )
            }
            assertEquals(
                ControlErrorCode.INVALID_ARGUMENT,
                assertIs<OperationResult.Failure>(session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(-1))).error.code,
                "$firmware negative prompt",
            )
            assertEquals(
                ControlErrorCode.INVALID_ARGUMENT,
                assertIs<OperationResult.Failure>(session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Toggle(false))).error.code,
                "$firmware wrong prompt type",
            )
            assertEquals(before, link.writes.size, "$firmware")
            assertTrue(session.state.value.settingOperations.isEmpty(), "$firmware")
            session.disconnect()
        }
    }

    @Test
    fun supportedGainProtectionAndPromptSettingsConfirmThroughTheSession() = runTest {
        val (session, link) = ready(firmware = "dd00040d020103f4aa")
        for ((setting, value, write, query, reply) in listOf(
            SettingVector(DeviceSetting.GAIN, FieldValue.Number(2), "ff040245024caa", "ff0502fa4545aa", "dd0002450226aa"),
            SettingVector(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(6), "ff0602460653aa", "ff0702fa4648aa", "dd000246062baa"),
        )) {
            val pending = async { session.set(setting, value) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2), setting.name)
            assertFalse(pending.isCompleted, setting.name)
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), setting.name)
            assertEquals(
                setOf(if (setting == DeviceSetting.GAIN) DeviceField.GAIN else DeviceField.HEARING_PROTECTION),
                session.state.value.settingOperations.last().confirmedFields,
                setting.name,
            )
        }
        session.disconnect()

        for ((firmware, level, write, reply) in listOf(
            PromptVector("dd00040d030003f4aa", 5, "ff04022e0538aa", "dd00022e0512aa"),
            PromptVector("dd00040d030004f5aa", 0, "ff04022e0033aa", "dd00022e000daa"),
        )) {
            val (promptSession, promptLink) = ready(firmware = firmware)
            val pending = async { promptSession.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level)) }
            runCurrent()
            assertEquals(listOf(write, "ff0502fa2e2eaa"), promptLink.writes.takeLast(2), "$firmware/$level")
            assertFalse(pending.isCompleted)
            promptLink.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(setOf(DeviceField.PROMPT_VOLUME), promptSession.state.value.settingOperations.single().confirmedFields)
            promptSession.disconnect()
        }
    }

    @Test
    fun bassAndLanguageRequireFreshMatchingReadback() = runTest {
        for (vector in listOf(
            CapabilityVector(ModelProfile.BUDSFEEL_MK2_RS_V2, DeviceSetting.BASS_BOOST, FieldValue.Number(1), "dd00032a050110aa"),
            CapabilityVector(ModelProfile.JASMINE_RS_V2, DeviceSetting.PROMPT_LANGUAGE, FieldValue.Text("english"), "dd0002310111aa"),
        )) {
            val (session, link) = ready(profile = vector.profile)
            // 合成：在提交设置前缓存相同值，不能将它作为本次写入的确认。
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            val pending = async { session.set(vector.setting, vector.requested) }
            runCurrent()
            assertFalse(pending.isCompleted)
            // 合成：普通 EQ 回包既不是低音档位，也不是语言状态。
            link.incoming.send("dd00022a0009aa".bytes())
            runCurrent()
            assertFalse(pending.isCompleted)
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(SettingStage.CONFIRMED, session.state.value.settingOperations.single().stage)
            session.disconnect()
        }
    }

    @Test
    fun newModelLimitsRejectUnsupportedControlsAndMalformedValuesBeforeWriting() = runTest {
        for (vector in listOf(
            RejectedCapability(ModelProfile.BUDSFEEL_MK2_RS_V2, DeviceSetting.BASS_BOOST, listOf(FieldValue.Number(-1), FieldValue.Number(5), FieldValue.Toggle(true)), DeviceSetting.PROMPT_LANGUAGE),
            RejectedCapability(ModelProfile.JASMINE_RS_V2, DeviceSetting.PROMPT_LANGUAGE, listOf(FieldValue.Text("french"), FieldValue.Number(0)), DeviceSetting.BASS_BOOST),
            RejectedCapability(ModelProfile.OPENFEEL_MK2_RS_V2, DeviceSetting.BASS_BOOST, listOf(FieldValue.Number(5)), DeviceSetting.PROMPT_LANGUAGE),
            RejectedCapability(ModelProfile.SWIFT2_RS_V2, DeviceSetting.GAME_MODE, listOf(FieldValue.Number(1)), DeviceSetting.TOUCH_BINDING),
            RejectedCapability(ModelProfile.I7_RS_V2, DeviceSetting.GAIN, listOf(FieldValue.Number(3)), DeviceSetting.BASS_BOOST),
        )) {
            val (session, link) = ready(profile = vector.profile)
            val before = link.writes.toList()
            for (value in vector.invalid) {
                assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(vector.setting, value)).error.code)
            }
            assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(vector.unsupported, FieldValue.Number(0))).error.code)
            assertEquals(before, link.writes)
            assertTrue(session.state.value.settingOperations.isEmpty())
            session.disconnect()
        }
    }

    private suspend fun TestScope.ready(capacity: Int = 16, firmware: String? = "dd00040d020103f4aa", profile: ModelProfile = ModelProfile.I7_RS_V2): Pair<ControlSession, AdvancedLink> {
        val link = AdvancedLink(capacity, firmware)
        val transport = object : ControlTransport {
            val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
            override fun scan(): Flow<Advertisement> = advertisements
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = link
        }
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
        )
        session.startScan()
        val prefix = profile.spec.advertisementPrefixes.first().bytes()
        val companyId = (prefix[0].toInt() and 255) or ((prefix[1].toInt() and 255) shl 8)
        transport.advertisements.emit(Advertisement(DeviceId("i7"), manufacturerData = mapOf(companyId to prefix.copyOfRange(2, prefix.size))))
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(DeviceId("i7")))
        link.initializing = false
        return session to link
    }

    private class AdvancedLink(override val maxWriteSize: Int, val firmware: String?) : ControlConnection {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        val writes = mutableListOf<String>()
        var initializing = true
        var gateAt: Int? = null
        var failAt: Int? = null
        val writeGate = CompletableDeferred<Unit>()
        override suspend fun subscribe() = Unit
        override suspend fun write(bytes: ByteArray) {
            writes += bytes.hex()
            if (writes.size == failAt) error("Synthetic transport write failure")
            if (writes.size == gateAt) writeGate.await()
            if (initializing) {
                incoming.send("dd00022a0009aa".bytes())
                firmware?.let { incoming.send(it.bytes()) }
            }
        }
        override suspend fun close() {
            incoming.close()
        }
    }

    private data class CapabilityVector(val profile: ModelProfile, val setting: DeviceSetting, val requested: FieldValue, val reply: String)

    private data class RejectedCapability(val profile: ModelProfile, val setting: DeviceSetting, val invalid: List<FieldValue>, val unsupported: DeviceSetting)

    private data class SettingVector(
        val setting: DeviceSetting,
        val value: FieldValue,
        val write: String,
        val query: String,
        val reply: String,
    )

    private data class PromptVector(val firmware: String, val level: Int, val write: String, val reply: String)

    companion object {
        private val curve = FieldValue.IntegerList(listOf(-6, -3, -1, 0, 1, 2, 3, 4, 5, 6))
        private const val CURVE_REPLY = "dd000b3e86838100010203040506c5aa"
        private fun reading(session: ControlSession, field: DeviceField) = assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(field)).value
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
