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

/** Public session with independent sender literals and synthetic device notifications. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionDeviceControlsTest {
    @Test
    fun i5AudioSelectionConfirmsBothCoupledFieldsOnlyAfterReadback() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        val pending = async { session.set(DeviceSetting.AUDIO_MODE, FieldValue.Text("aac_sbc")) }
        runCurrent()

        assertEquals(listOf("08ee00000008810b00008a", "08ee00000008010a0009"), link.writes.drop(13))
        assertFalse(pending.isCompleted)
        assertEquals(FieldValue.Text("ldac"), reading(session, DeviceField.AUDIO_MODE))
        assertEquals(FieldValue.Toggle(false), reading(session, DeviceField.MULTIPOINT))
        link.incoming.send("09ff00000108010b00001d".bytes())
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        assertEquals(FieldValue.Text("aac_sbc"), reading(session, DeviceField.AUDIO_MODE))
        assertEquals(FieldValue.Toggle(true), reading(session, DeviceField.MULTIPOINT))
        session.disconnect()
    }

    @Test
    fun touchConfirmationProjectsOnlyTheRequestedSlotAndAllowsOtherUnknownActions() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        val assignment = FieldValue.TouchAssignment("left", "single", "play_pause")
        val pending = async { session.set(DeviceSetting.TOUCH_BINDING, assignment) }
        runCurrent()
        assertEquals(listOf("08ee00000004810d000000058d", "08ee00000004010a0005"), link.writes.drop(13))
        assertFalse(pending.isCompleted)
        link.incoming.send("09ff00000104011200050f0f0f0f0f0f0f8e".bytes())
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        val actual = assertIs<FieldValue.TouchKeys>(reading(session, DeviceField.TOUCH_KEYS))
        assertEquals("play_pause", actual.bindings.single { it.side == "left" && it.gesture == "single" }.action)
        assertTrue(actual.bindings.filterNot { it.side == "left" && it.gesture == "single" }.all { it.action == null })
        session.disconnect()
    }

    @Test
    fun detectionSwitchConfirmsConfigurationWithoutInventingWearOrCaseEvents() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        val before = requireNotNull(session.state.value.device).fields
        val pending = async { session.set(DeviceSetting.EAR_DETECTION, FieldValue.Toggle(true)) }
        runCurrent()
        assertEquals(listOf("08ee0000000c810b00008e", "08ee0000000c010a000d"), link.writes.takeLast(2))
        assertFalse(pending.isCompleted)
        assertEquals(before, session.state.value.device?.fields)
        link.incoming.send("09ff0000010c010b000021".bytes())
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        assertEquals(FieldValue.Toggle(true), reading(session, DeviceField.EAR_DETECTION))
        assertEquals(before - DeviceField.EAR_DETECTION, requireNotNull(session.state.value.device).fields - DeviceField.EAR_DETECTION)
        session.disconnect()
    }

    @Test
    fun i5PromptVolumeSupportsAllFiveLevelsWithoutAnOffLevel() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        for ((level, write, reply) in listOf(
            Triple(1, "08ee0000000e830b000092", "09ff0000010e030b000025"),
            Triple(2, "08ee0000000e830b000193", "09ff0000010e030b000126"),
            Triple(3, "08ee0000000e830b000294", "09ff0000010e030b000227"),
            Triple(4, "08ee0000000e830b000395", "09ff0000010e030b000328"),
            Triple(5, "08ee0000000e830b000496", "09ff0000010e030b000429"),
        )) {
            val before = reading(session, DeviceField.PROMPT_VOLUME)
            val pending = async { session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level)) }
            runCurrent()
            assertEquals(listOf(write, "08ee0000000e030a0011"), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            assertEquals(before, reading(session, DeviceField.PROMPT_VOLUME))
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Number(level), reading(session, DeviceField.PROMPT_VOLUME))
        }
        session.disconnect()
    }

    @Test
    fun i5AllEightTouchSlotsAndActionsUseTheirModelMapping() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        for ((side, gesture, action, write, query, reply) in listOf(
            TouchVector("left", "single", "volume_up", "08ee00000004810d0000000088", "08ee00000004010a0005", "09ff00000104011200000f0f0f0f0f0f0f89"),
            TouchVector("left", "double", "volume_down", "08ee00000004810d000001018a", "08ee00000004010a0005", "09ff000001040112000f010f0f0f0f0f0f8a"),
            TouchVector("left", "triple", "previous_track", "08ee00000004810d000002028c", "08ee00000004010a0005", "09ff000001040112000f0f020f0f0f0f0f8b"),
            TouchVector("left", "long", "next_track", "08ee00000004810d000003038e", "08ee00000004010a0005", "09ff000001040112000f0f0f030f0f0f0f8c"),
            TouchVector("right", "single", "voice_assistant", "08ee00000004810d000100048d", "08ee00000004010a0005", "09ff000001040112000f0f0f0f040f0f0f8d"),
            TouchVector("right", "double", "play_pause", "08ee00000004810d000101058f", "08ee00000004010a0005", "09ff000001040112000f0f0f0f0f050f0f8e"),
            TouchVector("right", "triple", "none", "08ee00000004810d0001020691", "08ee00000004010a0005", "09ff000001040112000f0f0f0f0f0f060f8f"),
            TouchVector("right", "long", "noise_cycle", "08ee00000004810d0001030793", "08ee00000004010a0005", "09ff000001040112000f0f0f0f0f0f0f0790"),
        )) {
            val pending = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment(side, gesture, action)) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2), "$side/$gesture/$action")
            assertFalse(pending.isCompleted)
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), "$side/$gesture/$action")
        }
        assertEquals(8, session.state.value.device?.capabilities?.touchControl?.slots?.size)
        session.disconnect()
    }

    @Test
    fun i7AllEightSlotsAndNineActionsConfirmFromFragmentedModelReplies() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        for ((side, gesture, action, write, query, reply) in listOf(
            TouchVector("left", "single", "none", "ff040301010008aa", "ff0502fa0101aa", "dd001101010002ff03ff04ff11ff12ff13ff14ff3caa"),
            TouchVector("left", "double", "play_pause", "ff06030102010caa", "ff0702fa0103aa", "dd00110101ff020103ff04ff11ff12ff13ff14ff3daa"),
            TouchVector("left", "triple", "previous_track", "ff080301030210aa", "ff0902fa0105aa", "dd00110101ff02ff030204ff11ff12ff13ff14ff3eaa"),
            TouchVector("left", "long", "next_track", "ff0a0301040314aa", "ff0b02fa0107aa", "dd00110101ff02ff03ff040311ff12ff13ff14ff3faa"),
            TouchVector("right", "single", "volume_up", "ff0c0301110424aa", "ff0d02fa0109aa", "dd00110101ff02ff03ff04ff110412ff13ff14ff40aa"),
            TouchVector("right", "double", "volume_down", "ff0e0301120528aa", "ff0f02fa010baa", "dd00110101ff02ff03ff04ff11ff120513ff14ff41aa"),
            TouchVector("right", "triple", "game_mode", "ff10030113062caa", "ff1102fa010daa", "dd00110101ff02ff03ff04ff11ff12ff130614ff42aa"),
            TouchVector("right", "long", "voice_assistant", "ff120301140730aa", "ff1302fa010faa", "dd00110101ff02ff03ff04ff11ff12ff13ff140743aa"),
            TouchVector("right", "long", "noise_cycle", "ff140301140833aa", "ff1502fa0111aa", "dd00110101ff02ff03ff04ff11ff12ff13ff140844aa"),
        )) {
            val pending = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment(side, gesture, action)) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2), "$side/$gesture/$action")
            link.incoming.send(reply.take(20).bytes())
            runCurrent()
            assertFalse(pending.isCompleted, "$side/$gesture/$action")
            link.incoming.send(reply.drop(20).bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), "$side/$gesture/$action")
        }
        session.disconnect()
    }

    @Test
    fun unknownTargetTouchActionWaitsForItsKnownReadbackInBothModels() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val pending = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "play_pause")) }
            runCurrent()
            link.incoming.send(unknownTouchReply(profile).bytes())
            runCurrent()
            assertFalse(pending.isCompleted, profile.name)
            assertEquals(SettingStage.WRITTEN, session.state.value.settingOperations.single().stage)
            val known = if (profile == ModelProfile.I5_ZT) "09ff00000104011200050f0f0f0f0f0f0f8e" else "dd001101010102ff03ff04ff11ff12ff13ff14ff3daa"
            link.incoming.send(known.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), profile.name)
            session.disconnect()
        }
    }

    @Test
    fun onlyUnknownTargetTouchActionTimesOutInsteadOfReportingNotApplied() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val pending = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "play_pause")) }
            runCurrent()
            link.incoming.send(unknownTouchReply(profile).bytes())
            runCurrent()
            advanceTimeBy(51)
            runCurrent()
            assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code, profile.name)
            assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage)
        }
    }

    @Test
    fun knownDifferentTargetTouchActionReportsActualMismatch() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val pending = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "play_pause")) }
            runCurrent()
            val reply = if (profile == ModelProfile.I5_ZT) "09ff00000104011200000f0f0f0f0f0f0f89" else "dd001101010002ff03ff04ff11ff12ff13ff14ff3caa"
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(ControlErrorCode.NOT_APPLIED, assertIs<OperationResult.Failure>(pending.await()).error.code, profile.name)
            val actual = assertIs<FieldValue.TouchKeys>(reading(session, DeviceField.TOUCH_KEYS)).bindings.first()
            assertEquals(if (profile == ModelProfile.I5_ZT) "volume_up" else "none", actual.action)
            session.disconnect()
        }
    }

    @Test
    fun i5AudioAndMultipointSelectionsConfirmTheCouplingInBothDirections() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        assertEquals(true, session.state.value.device?.capabilities?.audioControl?.multipointCoupled)
        for ((setting, requested, write, mode, multipoint, reply) in listOf(
            AudioVector(DeviceSetting.AUDIO_MODE, FieldValue.Text("aac_sbc"), "08ee00000008810b00008a", "aac_sbc", true, "09ff00000108010b00001d"),
            AudioVector(DeviceSetting.MULTIPOINT, FieldValue.Toggle(false), "08ee00000008810b00018b", "ldac", false, "09ff00000108010b00011e"),
            AudioVector(DeviceSetting.AUDIO_MODE, FieldValue.Text("ldac"), "08ee00000008810b00018b", "ldac", false, "09ff00000108010b00011e"),
            AudioVector(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true), "08ee00000008810b00008a", "aac_sbc", true, "09ff00000108010b00001d"),
        )) {
            val beforeAudio = reading(session, DeviceField.AUDIO_MODE)
            val beforeMultipoint = reading(session, DeviceField.MULTIPOINT)
            val pending = async { session.set(setting, requested) }
            runCurrent()
            assertEquals(listOf(write, "08ee00000008010a0009"), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            assertEquals(beforeAudio, reading(session, DeviceField.AUDIO_MODE))
            assertEquals(beforeMultipoint, reading(session, DeviceField.MULTIPOINT))
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Text(mode), reading(session, DeviceField.AUDIO_MODE))
            assertEquals(FieldValue.Toggle(multipoint), reading(session, DeviceField.MULTIPOINT))
        }
        session.disconnect()
    }

    @Test
    fun i7AllAudioModesAndMultipointOperateIndependently() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        assertEquals(false, session.state.value.device?.capabilities?.audioControl?.multipointCoupled)
        for ((mode, write, query, reply) in listOf(
            ModeVector("aac_sbc", "ff04022b0030aa", "ff0502fa2b2baa", "dd00022b000aaa"),
            ModeVector("ldac", "ff06022b0133aa", "ff0702fa2b2daa", "dd00022b010baa"),
            ModeVector("lhdc", "ff08022b0236aa", "ff0902fa2b2faa", "dd00022b020caa"),
            ModeVector("lc3", "ff0a022b0339aa", "ff0b02fa2b31aa", "dd00022b030daa"),
        )) {
            val previousMode = reading(session, DeviceField.AUDIO_MODE)
            val previousMultipoint = reading(session, DeviceField.MULTIPOINT)
            val pending = async { session.set(DeviceSetting.AUDIO_MODE, FieldValue.Text(mode)) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2), mode)
            assertEquals(previousMode, reading(session, DeviceField.AUDIO_MODE))
            assertFalse(pending.isCompleted)
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), mode)
            assertEquals(FieldValue.Text(mode), reading(session, DeviceField.AUDIO_MODE))
            assertEquals(previousMultipoint, reading(session, DeviceField.MULTIPOINT))
        }
        for ((enabled, write, query, reply) in listOf(
            SwitchVector(DeviceSetting.MULTIPOINT, true, "ff0c02320140aa", "ff0d02fa323aaa", "dd0002320112aa"),
            SwitchVector(DeviceSetting.MULTIPOINT, false, "ff0e02320041aa", "ff0f02fa323caa", "dd0002320011aa"),
        ).map { MultipointVector(it.enabled, it.write, it.query, it.reply) }) {
            val pending = async { session.set(DeviceSetting.MULTIPOINT, FieldValue.Toggle(enabled)) }
            runCurrent()
            assertEquals(listOf(write, query), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            link.incoming.send(reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Toggle(enabled), reading(session, DeviceField.MULTIPOINT))
            assertEquals(FieldValue.Text("lc3"), reading(session, DeviceField.AUDIO_MODE))
        }
        session.disconnect()
    }

    @Test
    fun touchAndDetectionSwitchesUseInversePolarityWithoutChangingOtherFacts() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val vectors = if (profile == ModelProfile.I5_ZT) {
                listOf(
                    SwitchVector(DeviceSetting.TOUCH_ENABLED, false, "08ee0000000a810b00018d", "08ee0000000a010a000b", "09ff0000010a010b000120"),
                    SwitchVector(DeviceSetting.TOUCH_ENABLED, true, "08ee0000000a810b00008c", "08ee0000000a010a000b", "09ff0000010a010b00001f"),
                    SwitchVector(DeviceSetting.EAR_DETECTION, true, "08ee0000000c810b00008e", "08ee0000000c010a000d", "09ff0000010c010b000021"),
                    SwitchVector(DeviceSetting.EAR_DETECTION, false, "08ee0000000c810b00018f", "08ee0000000c010a000d", "09ff0000010c010b000122"),
                )
            } else {
                listOf(
                    SwitchVector(DeviceSetting.TOUCH_ENABLED, false, "ff040207010daa", "ff0502fa0707aa", "dd00020701e7aa"),
                    SwitchVector(DeviceSetting.TOUCH_ENABLED, true, "ff060207000eaa", "ff0702fa0709aa", "dd00020700e6aa"),
                    SwitchVector(DeviceSetting.EAR_DETECTION, true, "ff0802080011aa", "ff0902fa080caa", "dd00020800e7aa"),
                    SwitchVector(DeviceSetting.EAR_DETECTION, false, "ff0a02080114aa", "ff0b02fa080eaa", "dd00020801e8aa"),
                )
            }
            for ((setting, enabled, write, query, reply) in vectors) {
                val field = if (setting == DeviceSetting.TOUCH_ENABLED) DeviceField.TOUCH_ENABLED else DeviceField.EAR_DETECTION
                val before = requireNotNull(session.state.value.device).fields
                val pending = async { session.set(setting, FieldValue.Toggle(enabled)) }
                runCurrent()
                assertEquals(listOf(write, query), link.writes.takeLast(2), "$profile/$setting/$enabled")
                assertFalse(pending.isCompleted)
                link.incoming.send(reply.bytes())
                runCurrent()
                assertEquals(OperationResult.Success, pending.await(), "$profile/$setting/$enabled")
                assertEquals(FieldValue.Toggle(enabled), reading(session, field))
                assertEquals(before - field, requireNotNull(session.state.value.device).fields - field)
            }
            session.disconnect()
        }
    }

    @Test
    fun unsupportedOptionsInvalidTouchSlotsAndVolumeBoundariesNeverReachTheWire() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val count = link.writes.size
            for ((setting, value) in listOf(
                DeviceSetting.TOUCH_BINDING to FieldValue.Text("play_pause"),
                DeviceSetting.TOUCH_BINDING to FieldValue.TouchAssignment("middle", "single", "none"),
                DeviceSetting.TOUCH_BINDING to FieldValue.TouchAssignment("left", "quadruple", "none"),
                DeviceSetting.TOUCH_ENABLED to FieldValue.Number(1),
                DeviceSetting.EAR_DETECTION to FieldValue.Text("on"),
                DeviceSetting.AUDIO_MODE to FieldValue.Toggle(true),
                DeviceSetting.MULTIPOINT to FieldValue.Number(1),
            )) {
                assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(setting, value)).error.code, "$profile/$setting")
            }
            assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "find"))).error.code)
            assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.AUDIO_MODE, FieldValue.Text("spatial"))).error.code)
            if (profile == ModelProfile.I5_ZT) {
                for (value in listOf(FieldValue.Number(0), FieldValue.Number(6), FieldValue.Toggle(true))) {
                    assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(DeviceSetting.PROMPT_VOLUME, value)).error.code)
                }
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "game_mode"))).error.code)
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.AUDIO_MODE, FieldValue.Text("lhdc"))).error.code)
            } else {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.GAIN, FieldValue.Number(1))).error.code)
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(1))).error.code)
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(0))).error.code)
                assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(6))).error.code)
                assertEquals(persistentSetOf(), session.state.value.device?.capabilities?.gainLevels)
                assertEquals(persistentSetOf(1, 2, 3, 4, 5), session.state.value.device?.capabilities?.promptVolumeLevels)
            }
            assertEquals(count, link.writes.size)
            assertTrue(session.state.value.settingOperations.isEmpty())
            session.disconnect()
            assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(session.set(DeviceSetting.TOUCH_ENABLED, FieldValue.Toggle(true))).error.code)
        }
    }

    @Test
    fun disconnectEndsTheWholeControlQueue() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        val touch = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "play_pause")) }
        runCurrent()
        val volume = async { session.set(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(5)) }
        val audio = async { session.set(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true)) }
        runCurrent()
        assertEquals(listOf(SettingStage.WRITTEN, SettingStage.SUBMITTED, SettingStage.SUBMITTED), session.state.value.settingOperations.map { it.stage })
        assertEquals(15, link.writes.size)
        session.disconnect()
        runCurrent()
        for (pending in listOf(touch, volume, audio)) assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertTrue(session.state.value.settingOperations.all { it.stage == SettingStage.DISCONNECTED })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(15, link.writes.size)
    }

    @Test
    fun i7MissingTargetSlotCannotUseTheCachedMatchingBinding() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        val pending = async { session.set(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "none")) }
        runCurrent()
        // Complete synthetic frame omits slot01; its seven other slots cannot refresh the target.
        link.incoming.send("dd000f01020003000400110012001300140040aa".bytes())
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
    }

    private data class AudioVector(val setting: DeviceSetting, val requested: FieldValue, val write: String, val mode: String, val multipoint: Boolean, val reply: String)
    private data class ModeVector(val mode: String, val write: String, val query: String, val reply: String)
    private data class SwitchVector(val setting: DeviceSetting, val enabled: Boolean, val write: String, val query: String, val reply: String)
    private data class MultipointVector(val enabled: Boolean, val write: String, val query: String, val reply: String)

    private data class TouchVector(val side: String, val gesture: String, val action: String, val write: String, val query: String, val reply: String)

    private suspend fun TestScope.ready(profile: ModelProfile): Pair<ControlSession, DeviceControlLink> {
        val link = DeviceControlLink(profile)
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

    private class DeviceControlLink(val profile: ModelProfile) : ControlConnection {
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
                    incoming.send(
                        (
                            "dd00020901e9aa" + "dd00022b000aaa" + "dd0002320011aa" +
                                "dd00020700e6aa" + "dd00020801e8aa" + "dd0011010100020003000400110012001300140043aa"
                            ).bytes(),
                    )
                }
            }
        }
        override suspend fun close() {
            incoming.close()
        }
    }

    companion object {
        private fun unknownTouchReply(profile: ModelProfile) = if (profile == ModelProfile.I5_ZT) "09ff000001040112000f0f0f0f0f0f0f0f98" else "dd00110101ff02ff03ff04ff11ff12ff13ff14ff3baa"
        private fun reading(session: ControlSession, field: DeviceField) = assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(field)).value
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Independent Linux initialization capture; settings above use synthetic RX.
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
