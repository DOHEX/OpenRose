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
    fun touchConfirmationProjectsOnlyTheRequestedSlotAndAllowsOtherUnknownActions() = runTest {
        val (session, link) = ready(ModelProfile.I5_ZT)
        val assignment = FieldValue.TouchAssignment("left", "single", "play_pause")
        val pending = async { session.set(HeadsetCommand.TouchBinding(assignment.side, assignment.gesture, assignment.action)) }
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
            val pending = async { session.set(HeadsetCommand.PromptVolume(level)) }
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
        for (vector in listOf(
            TouchVector("left", "single", "volume_up", "08ee00000004810d0000000088", "08ee00000004010a0005", "09ff00000104011200000f0f0f0f0f0f0f89"),
            TouchVector("left", "double", "volume_down", "08ee00000004810d000001018a", "08ee00000004010a0005", "09ff000001040112000f010f0f0f0f0f0f8a"),
            TouchVector("left", "triple", "previous_track", "08ee00000004810d000002028c", "08ee00000004010a0005", "09ff000001040112000f0f020f0f0f0f0f8b"),
            TouchVector("left", "long", "next_track", "08ee00000004810d000003038e", "08ee00000004010a0005", "09ff000001040112000f0f0f030f0f0f0f8c"),
            TouchVector("right", "single", "voice_assistant", "08ee00000004810d000100048d", "08ee00000004010a0005", "09ff000001040112000f0f0f0f040f0f0f8d"),
            TouchVector("right", "double", "play_pause", "08ee00000004810d000101058f", "08ee00000004010a0005", "09ff000001040112000f0f0f0f0f050f0f8e"),
            TouchVector("right", "triple", "none", "08ee00000004810d0001020691", "08ee00000004010a0005", "09ff000001040112000f0f0f0f0f0f060f8f"),
            TouchVector("right", "long", "noise_cycle", "08ee00000004810d0001030793", "08ee00000004010a0005", "09ff000001040112000f0f0f0f0f0f0f0790"),
        )) {
            val slot = "${vector.side}/${vector.gesture}/${vector.action}"
            val assignment = FieldValue.TouchAssignment(vector.side, vector.gesture, vector.action)
            val pending = async { session.set(HeadsetCommand.TouchBinding(assignment.side, assignment.gesture, assignment.action)) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2), slot)
            assertFalse(pending.isCompleted)
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), slot)
        }
        session.disconnect()
    }

    @Test
    fun i7AllEightSlotsAndNineActionsConfirmFromFragmentedModelReplies() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        for (vector in listOf(
            TouchVector("left", "single", "none", "ff030301010007aa", "ff0402fa0100aa", "dd001101010002ff03ff04ff11ff12ff13ff14ff3caa"),
            TouchVector("left", "double", "play_pause", "ff05030102010baa", "ff0602fa0102aa", "dd00110101ff020103ff04ff11ff12ff13ff14ff3daa"),
            TouchVector("left", "triple", "previous_track", "ff07030103020faa", "ff0802fa0104aa", "dd00110101ff02ff030204ff11ff12ff13ff14ff3eaa"),
            TouchVector("left", "long", "next_track", "ff090301040313aa", "ff0a02fa0106aa", "dd00110101ff02ff03ff040311ff12ff13ff14ff3faa"),
            TouchVector("right", "single", "volume_up", "ff0b0301110423aa", "ff0c02fa0108aa", "dd00110101ff02ff03ff04ff110412ff13ff14ff40aa"),
            TouchVector("right", "double", "volume_down", "ff0d0301120527aa", "ff0e02fa010aaa", "dd00110101ff02ff03ff04ff11ff120513ff14ff41aa"),
            TouchVector("right", "triple", "game_mode", "ff0f030113062baa", "ff1002fa010caa", "dd00110101ff02ff03ff04ff11ff12ff130614ff42aa"),
            TouchVector("right", "long", "voice_assistant", "ff11030114072faa", "ff1202fa010eaa", "dd00110101ff02ff03ff04ff11ff12ff13ff140743aa"),
            TouchVector("right", "long", "noise_cycle", "ff130301140832aa", "ff1402fa0110aa", "dd00110101ff02ff03ff04ff11ff12ff13ff140844aa"),
        )) {
            val slot = "${vector.side}/${vector.gesture}/${vector.action}"
            val assignment = FieldValue.TouchAssignment(vector.side, vector.gesture, vector.action)
            val pending = async { session.set(HeadsetCommand.TouchBinding(assignment.side, assignment.gesture, assignment.action)) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2), slot)
            link.incoming.send(vector.reply.take(20).bytes())
            runCurrent()
            assertFalse(pending.isCompleted, slot)
            link.incoming.send(vector.reply.drop(20).bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), slot)
        }
        session.disconnect()
    }

    @Test
    fun unknownTargetTouchActionWaitsForItsKnownReadbackInBothModels() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val pending = async { session.set(HeadsetCommand.TouchBinding("left", "single", "play_pause")) }
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
            val pending = async { session.set(HeadsetCommand.TouchBinding("left", "single", "play_pause")) }
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
            val pending = async { session.set(HeadsetCommand.TouchBinding("left", "single", "play_pause")) }
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
        for (vector in listOf(
            AudioVector(DeviceSetting.AUDIO_MODE, FieldValue.Text("aac_sbc"), "08ee00000008810b00008a", "aac_sbc", true, "09ff00000108010b00001d"),
            AudioVector(DeviceSetting.MULTIPOINT, FieldValue.Toggle(false), "08ee00000008810b00018b", "ldac", false, "09ff00000108010b00011e"),
            AudioVector(DeviceSetting.AUDIO_MODE, FieldValue.Text("ldac"), "08ee00000008810b00018b", "ldac", false, "09ff00000108010b00011e"),
            AudioVector(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true), "08ee00000008810b00008a", "aac_sbc", true, "09ff00000108010b00001d"),
        )) {
            val beforeAudio = reading(session, DeviceField.AUDIO_MODE)
            val beforeMultipoint = reading(session, DeviceField.MULTIPOINT)
            val pending = async { session.set(requireNotNull(HeadsetCommand.decode(vector.setting, vector.requested))) }
            runCurrent()
            assertEquals(listOf(vector.write, "08ee00000008010a0009"), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            assertEquals(beforeAudio, reading(session, DeviceField.AUDIO_MODE))
            assertEquals(beforeMultipoint, reading(session, DeviceField.MULTIPOINT))
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Text(vector.mode), reading(session, DeviceField.AUDIO_MODE))
            assertEquals(FieldValue.Toggle(vector.multipoint), reading(session, DeviceField.MULTIPOINT))
        }
        session.disconnect()
    }

    @Test
    fun i7AllAudioModesAndMultipointOperateIndependently() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        for (vector in listOf(
            ModeVector("aac_sbc", "ff03022b002faa", "ff0402fa2b2aaa", "dd00022b000aaa"),
            ModeVector("ldac", "ff05022b0132aa", "ff0602fa2b2caa", "dd00022b010baa"),
            ModeVector("lhdc", "ff07022b0235aa", "ff0802fa2b2eaa", "dd00022b020caa"),
            ModeVector("lc3", "ff09022b0338aa", "ff0a02fa2b30aa", "dd00022b030daa"),
        )) {
            val previousMode = reading(session, DeviceField.AUDIO_MODE)
            val previousMultipoint = reading(session, DeviceField.MULTIPOINT)
            val pending = async { session.set(HeadsetCommand.AudioMode(vector.mode)) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2), vector.mode)
            assertEquals(previousMode, reading(session, DeviceField.AUDIO_MODE))
            assertFalse(pending.isCompleted)
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await(), vector.mode)
            assertEquals(FieldValue.Text(vector.mode), reading(session, DeviceField.AUDIO_MODE))
            assertEquals(previousMultipoint, reading(session, DeviceField.MULTIPOINT))
        }
        for (vector in listOf(
            SwitchVector(DeviceSetting.MULTIPOINT, true, "ff0b0232013faa", "ff0c02fa3239aa", "dd0002320112aa"),
            SwitchVector(DeviceSetting.MULTIPOINT, false, "ff0d02320040aa", "ff0e02fa323baa", "dd0002320011aa"),
        )) {
            val pending = async { session.set(requireNotNull(HeadsetCommand.decode(vector.setting, FieldValue.Toggle(vector.enabled)))) }
            runCurrent()
            assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2))
            assertFalse(pending.isCompleted)
            link.incoming.send(vector.reply.bytes())
            runCurrent()
            assertEquals(OperationResult.Success, pending.await())
            assertEquals(FieldValue.Toggle(vector.enabled), reading(session, DeviceField.MULTIPOINT))
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
                    SwitchVector(DeviceSetting.TOUCH_ENABLED, false, "ff030207010caa", "ff0402fa0706aa", "dd00020701e7aa"),
                    SwitchVector(DeviceSetting.TOUCH_ENABLED, true, "ff050207000daa", "ff0602fa0708aa", "dd00020700e6aa"),
                    SwitchVector(DeviceSetting.EAR_DETECTION, true, "ff0702080010aa", "ff0802fa080baa", "dd00020800e7aa"),
                    SwitchVector(DeviceSetting.EAR_DETECTION, false, "ff0902080113aa", "ff0a02fa080daa", "dd00020801e8aa"),
                )
            }
            for (vector in vectors) {
                val description = "$profile/${vector.setting}/${vector.enabled}"
                val field = if (vector.setting == DeviceSetting.TOUCH_ENABLED) DeviceField.TOUCH_ENABLED else DeviceField.EAR_DETECTION
                val before = requireNotNull(session.state.value.device).diagnostics.fields
                val pending = async { session.set(requireNotNull(HeadsetCommand.decode(vector.setting, FieldValue.Toggle(vector.enabled)))) }
                runCurrent()
                assertEquals(listOf(vector.write, vector.query), link.writes.takeLast(2), description)
                assertFalse(pending.isCompleted)
                link.incoming.send(vector.reply.bytes())
                runCurrent()
                assertEquals(OperationResult.Success, pending.await(), description)
                assertEquals(FieldValue.Toggle(vector.enabled), reading(session, field))
                assertEquals(before - field, requireNotNull(session.state.value.device).diagnostics.fields - field)
            }
            session.disconnect()
        }
    }

    @Test
    fun unsupportedOptionsInvalidTouchSlotsAndVolumeBoundariesNeverReachTheWire() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val (session, link) = ready(profile)
            val count = link.writes.size
            for (command in listOf(
                HeadsetCommand.TouchBinding("middle", "single", "none"),
                HeadsetCommand.TouchBinding("left", "quadruple", "none"),
            )) {
                assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(session.set(command)).error.code)
            }
            assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.TouchBinding("left", "single", "find"))).error.code)
            assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.AudioMode("spatial"))).error.code)
            if (profile == ModelProfile.I5_ZT) {
                for (level in listOf(0, 6)) {
                    val outcome = session.set(HeadsetCommand.PromptVolume(level))
                    assertEquals(ControlErrorCode.INVALID_ARGUMENT, assertIs<OperationResult.Failure>(outcome).error.code)
                }
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.TouchBinding("left", "single", "game_mode"))).error.code)
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.AudioMode("lhdc"))).error.code)
            } else {
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.Gain(1))).error.code)
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.HearingProtection(1))).error.code)
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.PromptVolume(0))).error.code)
                // The firmware is unread, so prompt volume is not writable at all yet.
                assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.PromptVolume(6))).error.code)
            }
            assertEquals(count, link.writes.size)
            assertTrue(session.state.value.settingOperations.isEmpty())
            session.disconnect()
            assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(session.set(HeadsetCommand.TouchEnabled(true))).error.code)
        }
    }

    @Test
    fun i7MissingTargetSlotCannotUseTheCachedMatchingBinding() = runTest {
        val (session, link) = ready(ModelProfile.I7_RS_V2)
        val pending = async { session.set(HeadsetCommand.TouchBinding("left", "single", "none")) }
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
        private fun reading(session: ControlSession, field: DeviceField) = assertIs<FieldReading.Known>(session.state.value.device?.diagnostics?.fields?.get(field)).value
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
