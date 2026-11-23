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
        val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()
        assertEquals(listOf("ff03022a0432aa", "ff040b3e86838100010203040506ebaa", "ff0502fa2a2aaa"), link.writes.drop(3))
        assertFalse(pending.isCompleted)
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        assertEquals(2, session.state.value.settingOperations.single().writtenFrames)
        assertEquals(2, session.state.value.settingOperations.single().totalFrames)
        assertTrue(session.state.value.settingOperations.single().confirmedFields.isEmpty())
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals("ff0602fa3e3faa", link.writes.last())
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

        val result = session.set(HeadsetCommand.CustomEqualizer(curve.values))

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
            val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
            runCurrent()

            assertEquals(ControlErrorCode.FAILED, assertIs<OperationResult.Failure>(pending.await()).error.code)
            assertEquals(failedWrite, link.writes.size - before)
            val operation = session.state.value.settingOperations.single()
            assertEquals(failedWrite - 1, operation.writtenFrames)
            assertEquals(2, operation.totalFrames)
            assertEquals(SettingStage.FAILED, operation.stage)
            assertEquals(listOf("ff03022a0432aa", "ff040b3e86838100010203040506ebaa").take(failedWrite), link.writes.drop(before))
            assertEquals(ConnectionState.Disconnected, session.state.value.connection)
            assertEquals(null, session.state.value.device)
        }
    }

    @Test
    fun inFlightSecondWriteShowsPartialProgressAndDisconnectKeepsIt() = runTest {
        val (session, link) = ready()
        val before = link.writes.size
        link.gateAt = before + 2
        val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()

        val inProgress = session.state.value.settingOperations.single()
        assertEquals(SettingStage.PARTIALLY_WRITTEN, inProgress.stage)
        assertEquals(1, inProgress.writtenFrames)
        assertEquals(2, inProgress.totalFrames)
        assertEquals(listOf("ff03022a0432aa", "ff040b3e86838100010203040506ebaa"), link.writes.drop(before))

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
        val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()

        link.incoming.send("dd00022a050eaa".bytes())
        runCurrent()
        assertEquals("ff0602fa3e3faa", link.writes.last())
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
        val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()

        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()
        assertTrue(session.state.value.settingOperations.single().confirmedFields.isEmpty())
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals("ff0602fa3e3faa", link.writes.last())
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
            val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
            runCurrent()

            link.incoming.send(modeReply.bytes())
            runCurrent()
            advanceTimeBy(51)
            runCurrent()

            assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code, modeReply)
            assertEquals("ff0502fa2a2aaa", link.writes.last(), modeReply)
            assertEquals(emptySet(), session.state.value.settingOperations.single().confirmedFields, modeReply)
            assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage, modeReply)
        }
    }

    @Test
    fun disconnectAfterModeConfirmationKeepsItsEvidenceAndStopsThePlan() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals("ff0602fa3e3faa", link.writes.last())

        session.disconnect()
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        val operation = session.state.value.settingOperations.single()
        assertEquals(2, operation.writtenFrames)
        assertEquals(2, operation.totalFrames)
        assertEquals(setOf(DeviceField.EQ), operation.confirmedFields)
        assertEquals(SettingStage.DISCONNECTED, operation.stage)
        assertEquals("ff0602fa3e3faa", link.writes.last())
    }

    @Test
    fun modeThatBecomesUnknownBeforeFinalConfirmationTimesOutInsteadOfReportingSuccess() = runTest {
        val (session, link) = ready()
        val pending = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()
        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        assertEquals(setOf(DeviceField.EQ), session.state.value.settingOperations.single().confirmedFields)

        link.incoming.send("dd00022a0710aa".bytes())
        runCurrent()
        assertIs<FieldReading.Unknown>(session.state.value.device?.diagnostics?.fields?.get(DeviceField.EQ))
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
        val first = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()
        val second = async { session.set(HeadsetCommand.CustomEqualizer(curve.values)) }
        runCurrent()
        assertEquals(listOf(SettingStage.WRITTEN, SettingStage.SUBMITTED), session.state.value.settingOperations.map { it.stage })

        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()
        assertEquals(OperationResult.Success, first.await())
        val secondStart = link.writes.size - 3
        assertEquals(listOf("ff07022a0436aa", "ff080b3e86838100010203040506efaa", "ff0902fa2a2eaa"), link.writes.drop(secondStart))

        link.incoming.send("dd00022a040daa".bytes())
        runCurrent()
        link.incoming.send(CURVE_REPLY.bytes())
        runCurrent()

        assertEquals(OperationResult.Success, second.await())
        assertEquals("ff0a02fa3e43aa", link.writes.last())
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
                assertIs<OperationResult.Failure>(session.set(HeadsetCommand.CustomEqualizer(invalid.values))).error.code,
            )
        }
        assertEquals(before, link.writes.size)
        assertTrue(session.state.value.settingOperations.isEmpty())
        session.disconnect()
    }

    @Test
    fun sessionCapabilitiesAndValidationFollowTheFirmwareGates() = runTest {
        for ((firmware, gainAllowed, muteAllowed) in listOf(
            Triple("dd00040d020102f3aa", false, false),
            Triple(null, false, false),
            Triple("dd00040d030004f5aa", true, true),
        )) {
            val (session, link) = ready(firmware = firmware)
            val capabilities = requireNotNull(session.state.value.device).capabilities
            assertEquals(if (gainAllowed) setOf(0, 1, 2) else emptySet(), capabilities.gainLevels, "$firmware gain")
            assertEquals(if (gainAllowed) (0..6).toSet() else emptySet(), capabilities.hearingProtectionLevels, "$firmware protection")
            // Unread firmware leaves the levels unknown, and prompt volume is not writable until they are known.
            val promptLevels = when {
                muteAllowed -> (0..5).toSet()
                firmware == null -> emptySet()
                else -> (1..5).toSet()
            }
            assertEquals(promptLevels, capabilities.promptVolumeLevels, "$firmware prompt")
            val before = link.writes.size
            val gainResult = session.set(HeadsetCommand.Gain(if (gainAllowed) 3 else 0))
            val protectionResult = session.set(HeadsetCommand.HearingProtection(if (gainAllowed) 7 else 6))
            val promptResult = session.set(HeadsetCommand.PromptVolume(6))
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
            val badLevel = if (firmware == null) ControlErrorCode.UNSUPPORTED else ControlErrorCode.INVALID_ARGUMENT
            assertEquals(badLevel, assertIs<OperationResult.Failure>(promptResult).error.code, "$firmware prompt")
            if (!muteAllowed) {
                assertEquals(
                    ControlErrorCode.UNSUPPORTED,
                    assertIs<OperationResult.Failure>(session.set(HeadsetCommand.PromptVolume(0))).error.code,
                    "$firmware mute",
                )
            }
            assertEquals(
                badLevel,
                assertIs<OperationResult.Failure>(session.set(HeadsetCommand.PromptVolume(-1))).error.code,
                "$firmware negative prompt",
            )
            assertEquals(before, link.writes.size, "$firmware")
            assertTrue(session.state.value.settingOperations.isEmpty(), "$firmware")
            session.disconnect()
        }
    }

    @Test
    fun supportedGainProtectionAndPromptSettingsConfirmThroughTheSession() = runTest {
        val (session, link) = ready(firmware = "dd00040d020103f4aa")
        for (vector in listOf(
            SettingVector(DeviceSetting.GAIN, FieldValue.Number(2), "ff030245024baa", "ff0402fa4544aa", "dd0002450226aa"),
            SettingVector(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(6), "ff0502460652aa", "ff0602fa4647aa", "dd000246062baa"),
        )) {
            val pending = async { session.set(requireNotNull(HeadsetCommand.decode(vector.setting, vector.value))) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2), vector.setting.name)
            assertFalse(pending.isCompleted, vector.setting.name)
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), vector.setting.name)
            assertEquals(
                setOf(if (vector.setting == DeviceSetting.GAIN) DeviceField.GAIN else DeviceField.HEARING_PROTECTION),
                session.state.value.settingOperations.last().confirmedFields,
                vector.setting.name,
            )
        }
        session.disconnect()

        for (vector in listOf(
            PromptVector("dd00040d030003f4aa", 5, "ff03022e0537aa", "dd00022e0512aa"),
            PromptVector("dd00040d030004f5aa", 0, "ff03022e0032aa", "dd00022e000daa"),
        )) {
            val (promptSession, promptLink) = ready(firmware = vector.firmware)
            val pending = async { promptSession.set(HeadsetCommand.PromptVolume(vector.level)) }
            runCurrent()
            assertEquals(listOf(vector.write, "ff0402fa2e2daa"), promptLink.writes.takeLast(2), "${vector.firmware}/${vector.level}")
            assertFalse(pending.isCompleted)
            promptLink.incoming.send(vector.reply.bytes())
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
            val pending = async { session.set(requireNotNull(HeadsetCommand.decode(vector.setting, vector.requested))) }
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
            RejectedCapability(ModelProfile.BUDSFEEL_MK2_RS_V2, listOf(HeadsetCommand.BassBoost(-1), HeadsetCommand.BassBoost(5)), HeadsetCommand.PromptLanguage("english")),
            RejectedCapability(ModelProfile.JASMINE_RS_V2, listOf(HeadsetCommand.PromptLanguage("french")), HeadsetCommand.BassBoost(0)),
            RejectedCapability(ModelProfile.OPENFEEL_MK2_RS_V2, listOf(HeadsetCommand.BassBoost(3), HeadsetCommand.BassBoost(5)), HeadsetCommand.PromptLanguage("english")),
            RejectedCapability(ModelProfile.SWIFT2_RS_V2, emptyList(), HeadsetCommand.TouchBinding("left", "single", "none")),
            RejectedCapability(ModelProfile.I7_RS_V2, listOf(HeadsetCommand.Gain(3)), HeadsetCommand.BassBoost(0)),
        )) {
            val (session, link) = ready(profile = vector.profile)
            val before = link.writes.toList()
            for (invalid in vector.invalid) {
                assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(invalid)).error.code)
            }
            assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(vector.unsupported)).error.code)
            assertEquals(before, link.writes)
            assertTrue(session.state.value.settingOperations.isEmpty())
            session.disconnect()
        }
    }

    @Test
    fun aTapAssignmentWaitsForItsOwnFreshKeyTable() = runTest {
        val (session, link) = ready(profile = ModelProfile.ASTRO_X_RS_V2)
        // Synthetic 3C table already has the requested right triple action (07).
        val tapReply = "dd00093c13070201030212045aaa".bytes()
        link.incoming.send(tapReply)
        runCurrent()
        val pending = async { session.set(HeadsetCommand.TapBinding("right", "triple", "voice_assistant")) }
        runCurrent()
        assertFalse(pending.isCompleted)
        // 01 belongs to the ordinary touch sensor, even when all its actions decode.
        link.incoming.send("dd0011010100020003000400110012001300140043aa".bytes())
        runCurrent()
        assertFalse(pending.isCompleted)
        link.incoming.send(tapReply)
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        assertEquals(setOf(DeviceField.TAP_KEYS), session.state.value.settingOperations.single().confirmedFields)
        session.disconnect()
    }

    @Test
    fun firmwareChangesRetainObservedFieldsAndWithdrawOnlyLoadingFields() = runTest {
        val oldFirmware = "dd00040d010501f5aa"
        val (session, link) = ready(profile = ModelProfile.ASTRO_X_RS_V2, firmware = oldFirmware)
        val gain = "dd0002450226aa"
        val newFirmware = "dd00040d010502f6aa"
        link.incoming.send((gain + newFirmware).bytes())
        runCurrent()
        val enabled = requireNotNull(session.state.value.device)
        // The gain reply was diagnostic only when it arrived, even though the next frame unlocks gain.
        assertEquals(gain, enabled.diagnostics.unknownNotifications.last())
        assertTrue(DeviceField.GAIN in enabled.capabilities.readableFields)
        assertEquals(2, enabled.confirmedNumber(DeviceField.GAIN))
        assertEquals(FieldReading.Loading, enabled.reading(DeviceField.HEARING_PROTECTION))

        link.incoming.send(oldFirmware.bytes())
        runCurrent()
        val disabled = requireNotNull(session.state.value.device)
        assertFalse(DeviceField.GAIN in disabled.capabilities.readableFields)
        assertEquals(2, disabled.confirmedNumber(DeviceField.GAIN))
        assertEquals(null, disabled.reading(DeviceField.HEARING_PROTECTION))
        session.disconnect()
    }

    @Test
    fun changingFirstAngelGameModeRefreshesEqAndRejectsTheQueuedMusicPreset() = runTest {
        val (session, link) = ready(profile = ModelProfile.FIRST_ANGEL_RS_V2)
        link.incoming.send("dd00020e00edaa".bytes()) // Synthetic music mode.
        runCurrent()
        assertEquals(FieldValue.Text("hifi"), reading(session, DeviceField.EQ))
        assertEquals(setOf("pop", "hifi"), session.state.value.device?.capabilities?.equalizer?.presets.orEmpty().toSet())
        val before = link.writes.size
        val toggle = async { session.set(HeadsetCommand.GameMode(true)) }
        val oldPreset = async { session.set(HeadsetCommand.EqualizerPreset("hifi")) }
        runCurrent()
        val gameReply = "dd00020e01eeaa".bytes()
        link.incoming.send(gameReply)
        runCurrent()
        assertFalse(toggle.isCompleted)
        assertFalse(oldPreset.isCompleted)
        assertEquals(listOf("0e01", "fa0e", "fa2a"), link.writes.drop(before).map { it.substring(6, 10) })
        assertEquals(setOf("game_1"), session.state.value.device?.capabilities?.equalizer?.presets.orEmpty().toSet())
        assertEquals(FieldReading.Loading, session.state.value.device?.diagnostics?.fields?.get(DeviceField.EQ))
        link.incoming.send("dd00022aa0ccaa".bytes()) // Synthetic FA 2A response from the new bank.
        runCurrent()
        assertEquals(OperationResult.Success, toggle.await())
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(oldPreset.await()).error.code)
        assertEquals(FieldValue.Text("game_1"), reading(session, DeviceField.EQ))
        session.disconnect()
    }

    @Test
    fun gameModeRefreshDoesNotConsumeTheQueuedMokaPresetReadback() = runTest {
        val (session, link) = ready(profile = ModelProfile.MOKA_RS_V2)
        val before = link.writes.size
        val toggle = async { session.set(HeadsetCommand.GameMode(true)) }
        val preset = async { session.set(HeadsetCommand.EqualizerPreset("pop")) }
        runCurrent()
        link.incoming.send("dd00020e01eeaa".bytes())
        runCurrent()
        link.incoming.send("dd00022b000aaa".bytes()) // Audio mode cannot finish the EQ refresh.
        runCurrent()
        assertFalse(toggle.isCompleted)
        assertEquals(listOf("0e01", "fa0e", "fa2a"), link.writes.drop(before).map { it.substring(6, 10) })
        link.incoming.send("dd00022a0009aa".bytes()) // Earlier refresh reports HiFi.
        runCurrent()
        assertEquals(OperationResult.Success, toggle.await())
        assertFalse(preset.isCompleted)
        assertEquals(listOf("2a01", "fa2a"), link.writes.takeLast(2).map { it.substring(6, 10) })
        link.incoming.send("dd00022a010aaa".bytes()) // Queued write now receives its own POP confirmation.
        assertEquals(OperationResult.Success, preset.await())
        session.disconnect()
    }

    @Test
    fun missingGameModeRefreshTimesOutBeforeAQueuedPresetIsWritten() = runTest {
        val (session, link) = ready(profile = ModelProfile.MOKA_RS_V2)
        val before = link.writes.size
        val toggle = async { session.set(HeadsetCommand.GameMode(true)) }
        val preset = async { session.set(HeadsetCommand.EqualizerPreset("pop")) }
        runCurrent()
        link.incoming.send("dd00020e01eeaa".bytes())
        runCurrent()
        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(toggle.await()).error.code)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(preset.await()).error.code)
        assertEquals(listOf("0e01", "fa0e", "fa2a"), link.writes.drop(before).map { it.substring(6, 10) })
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

    private data class RejectedCapability(val profile: ModelProfile, val invalid: List<HeadsetCommand>, val unsupported: HeadsetCommand)

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
        private fun reading(session: ControlSession, field: DeviceField) = assertIs<FieldReading.Known>(session.state.value.device?.diagnostics?.fields?.get(field)).value
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
