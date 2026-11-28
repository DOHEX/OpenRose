package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.AcquireResult
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostFrame
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostPlatformFacts
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.ReleaseReceipt
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import io.github.dohex.openrose.ffi.encodeToBytes
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlSettings
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.ScanState
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class HostWireTest {
    private val generation = OwnerGeneration("incarnation-a", 3)
    private val holder = OwnerHolder(ControlWay.MODULE, generation, "io.github.dohex.openrose", ":control", uid = 10_123, pid = 4_567)
    private val reference = SessionReference(DeviceId("AA:BB:CC:DD:EE:01"), 7)

    @Test
    fun everyCommandSurvivesTheWire() {
        val commands = listOf(
            HostCommand.RefreshBondedDevices,
            HostCommand.StartScan,
            HostCommand.StopScan,
            HostCommand.SelectCurrentHeadset(DeviceId("AA:BB:CC:DD:EE:00")),
            HostCommand.Connect(DeviceId("AA:BB:CC:DD:EE:01")),
            HostCommand.ConnectFromSystem(DeviceId("AA:BB:CC:DD:EE:02")),
            HostCommand.ConnectForAddDevice(DeviceId("AA:BB:CC:DD:EE:03")),
            HostCommand.Disconnect,
            HostCommand.Set(reference, HeadsetCommand.GameMode(true)),
            HostCommand.Set(null, HeadsetCommand.GameMode(false)),
            HostCommand.Set(reference, HeadsetCommand.SpatialAudio(true)),
            HostCommand.ClaimPrompt(reference, "prompt-1"),
        )

        for (command in commands) {
            assertEquals(command, HostWire.decodeCommand(HostWire.encode(command)))
        }
    }

    @Test
    fun everyTypedHeadsetSettingKeepsItsPayloadAndSession() {
        val writes = listOf(
            HeadsetCommand.NoiseMode(NoiseMode.WIND), HeadsetCommand.AncLevel(3), HeadsetCommand.TransparencyLevel(2),
            HeadsetCommand.NoiseCycle(mapOf("anc" to true, "wind" to false)), HeadsetCommand.EqualizerPreset("hifi"),
            HeadsetCommand.CustomEqualizer(listOf(-2, 0, 3)), HeadsetCommand.GameMode(true), HeadsetCommand.AudioMode("ldac"),
            HeadsetCommand.Multipoint(false), HeadsetCommand.TouchEnabled(true), HeadsetCommand.EarDetection(true),
            HeadsetCommand.TouchBinding("left", "single", "none"), HeadsetCommand.TapEnabled(false),
            HeadsetCommand.TapBinding("right", "double", "voice_assistant"), HeadsetCommand.PromptVolume(0),
            HeadsetCommand.Gain(1), HeadsetCommand.HearingProtection(5), HeadsetCommand.BassBoost(3),
            HeadsetCommand.PromptLanguage("chinese"), HeadsetCommand.SpatialAudio(false), HeadsetCommand.SpatialMode("fixed"),
            HeadsetCommand.SpatialContent("movie"), HeadsetCommand.EarAdaptive(true),
            HeadsetCommand.IndependentMicActivation(true), HeadsetCommand.IndependentMicMode(false),
        )
        assertEquals(DeviceSetting.entries.toSet(), writes.map { it.setting }.toSet())
        for (write in writes) {
            val expected = HostCommand.Set(reference, write)
            assertEquals(expected, HostWire.decodeCommand(HostWire.encode(expected)))
        }
    }

    @Test
    fun malformedHeadsetSettingNeverReachesTheHost() {
        for ((setting, value) in listOf(
            DeviceSetting.BASS_BOOST to FieldValue.Toggle(true),
            DeviceSetting.PROMPT_LANGUAGE to FieldValue.Number(0),
            DeviceSetting.PROMPT_VOLUME to FieldValue.Toggle(false),
            DeviceSetting.NOISE_MODE to FieldValue.Toggle(true),
            DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(mapOf("normal" to true, "transparency" to null, "wind" to false, "anc" to true)),
            DeviceSetting.EQ to FieldValue.Number(0),
            DeviceSetting.EQ to FieldValue.Toggle(true),
            DeviceSetting.GAME_MODE to FieldValue.Number(1),
            DeviceSetting.GAME_MODE to FieldValue.Text("on"),
            DeviceSetting.TOUCH_BINDING to FieldValue.Text("play_pause"),
            DeviceSetting.TOUCH_ENABLED to FieldValue.Number(1),
            DeviceSetting.EAR_DETECTION to FieldValue.Text("on"),
            DeviceSetting.AUDIO_MODE to FieldValue.Toggle(true),
            DeviceSetting.MULTIPOINT to FieldValue.Number(1),
        )) {
            assertNull(HeadsetCommand.decode(setting, value), "$setting/$value")
            val malformed = Wire.Obj(
                mapOf(
                    "t" to Wire.Str("set"),
                    "reference" to Wire.Null,
                    "setting" to Wire.Str(setting.name),
                    "value" to ControlWire.encode(value),
                ),
            )
            assertFailsWith<WireFormatException> { HostWire.decodeCommand(malformed) }
        }
    }

    @Test
    fun aHostFrameCarriesPlatformFactsAndSnapshotBytesAcrossTheWire() {
        val snapshot = testSnapshot()
        val facts = HostPlatformFacts(PermissionState.GRANTED, TransportAvailability.Available, scanPermission = PermissionState.DENIED)
        val frame = HostFrame(generation, holder, facts, snapshot, SystemPresentationState(), HostFacts(presentation = mapOf("k" to "v")))

        val encoded = HostFrameWire.encode(frame) as Wire.Obj
        assertEquals(frame, HostFrameWire.decodeFrame(parseWire(encoded.render())))

        val platformObj = encoded.field("platform") as Wire.Obj
        val withoutScanPermission = Wire.Obj(encoded.fields + ("platform" to Wire.Obj(platformObj.fields - "scanPermission")))
        assertFailsWith<WireFormatException> { HostFrameWire.decodeFrame(withoutScanPermission) }

        for (transport in listOf(
            TransportAvailability.Unavailable(TransportUnavailableReason.NoAdapter),
            TransportAvailability.Unavailable(TransportUnavailableReason.UnsupportedPlatform("macOS")),
            TransportAvailability.Unavailable(TransportUnavailableReason.MissingDependency("libdbus")),
            TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable("dbus error")),
        )) {
            val unavailableFacts = HostPlatformFacts(PermissionState.GRANTED, transport)
            val unavailableFrame = frame.copy(platform = unavailableFacts)
            assertEquals(unavailableFacts, HostFrameWire.decodeFrame(HostFrameWire.encode(unavailableFrame)).platform)
        }
    }

    @Test
    fun malformedAndUnsupportedSnapshotBytesFailWithATypedException() {
        val snapshot = testSnapshot()
        val wire = HostFrameWire.encode(
            HostFrame(generation, holder, HostPlatformFacts(PermissionState.GRANTED, TransportAvailability.Available), snapshot, SystemPresentationState()),
        ) as Wire.Obj

        val malformedPayload = Wire.Obj(wire.fields + ("snapshot" to str(Base64.encode("corrupt".encodeToByteArray()))))
        val malformedError = assertFailsWith<HostSnapshotFormatException> { HostFrameWire.decodeFrame(malformedPayload) }
        assertIs<CoreDecodeResult.Error.Malformed>(malformedError.error)

        val wrongVersionBytes = snapshot.encodeToBytes().apply {
            this[3] = (coreWireFormatVersion().toInt() + 1).toByte()
        }
        val unsupportedPayload = Wire.Obj(wire.fields + ("snapshot" to str(Base64.encode(wrongVersionBytes))))
        val versionError = assertFailsWith<HostSnapshotFormatException> { HostFrameWire.decodeFrame(unsupportedPayload) }
        val unsupported = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(versionError.error)
        assertEquals(coreWireFormatVersion(), unsupported.expected)
        assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), unsupported.actual)

        val badBase64 = Wire.Obj(wire.fields + ("snapshot" to str("%%%")))
        assertFailsWith<WireFormatException> { HostFrameWire.decodeFrame(badBase64) }
    }

    private fun testSnapshot(): ControlSnapshot = ControlSnapshot(
        revision = 4u,
        platform = PlatformState(
            permission = uniffi.openrose_control.PermissionState.GRANTED,
            transport = uniffi.openrose_control.TransportAvailability.Available,
            hostPlatform = HostPlatform.ANDROID,
            userUnlocked = true,
            scanPermission = uniffi.openrose_control.PermissionState.DENIED,
            controlTransportApplicability = PlatformApplicability.APPLICABLE,
        ),
        settings = ControlSettings(forceBle = false),
        connection = ConnectionState.Disconnected,
        bondedHeadsets = emptyList(),
        candidates = emptyList(),
        scan = ScanState.Idle,
        headset = null,
        lastError = null,
        settingOperations = emptyList(),
        audioHeadsetId = reference.deviceId.value,
        connectionTarget = null,
        selectedHeadsetId = reference.deviceId.value,
        connectionRecovery = null,
        connectionBlockReason = null,
        reconnectGuidance = null,
    )

    @Test
    fun aCommandAddressedToAGenerationKeepsBothParts() {
        val command = HostCommand.ClaimPrompt(reference, "prompt-1")

        assertEquals(generation to command, HostWire.decodeCall(HostWire.encodeCall(generation, command)))
    }

    @Test
    fun aSwitchRequestWithoutAnObservedGenerationKeepsThatAbsence() {
        val request = SwitchRequest("switch-2", ControlWay.MODULE, ControlWay.APP)

        assertEquals(request, HandoffWire.decodeRequest(HandoffWire.encode(request)))
    }

    @Test
    fun everyCallResultSurvivesTheWire() {
        val results = listOf(
            HostCallResult.Flag(true),
            HostCallResult.Flag(false),
            HostCallResult.Rejected(HostRejection.STALE_GENERATION, "owner generation changed"),
        )

        for (result in results) {
            assertEquals(result, HostWire.decodeCallResult(HostWire.encode(result)))
        }
    }

    @Test
    fun aHolderWithoutProcessIdentityKeepsThatAbsence() {
        val anonymous = holder.copy(uid = null, pid = null)

        assertEquals(anonymous, HostIdentityWire.decodeHolder(HostIdentityWire.encode(anonymous)))
    }

    @Test
    fun hostStatusKeepsWhoHoldsTheSessionOrWhyNobodyDoes() {
        val statuses = listOf(
            HostStatus.Active(generation, holder),
            HostStatus.Inactive("incarnation-a", generation, "released by a switch request"),
            HostStatus.Inactive("incarnation-b", null, "module disabled"),
        )

        for (status in statuses) {
            assertEquals(status, HostIdentityWire.decodeStatus(HostIdentityWire.encode(status)))
        }
    }

    @Test
    fun releaseResultsKeepTheReceiptOrTheRefusalWithTheStatusItSaw() {
        val results = listOf(
            ReleaseResult.Released(ReleaseReceipt("switch-1", "incarnation-a", generation, predecessorEnded = true)),
            ReleaseResult.Released(ReleaseReceipt("switch-2", "incarnation-a", null)),
            ReleaseResult.Refused(HostRejection.FORBIDDEN, "role cannot release", HostStatus.Active(generation, holder)),
            ReleaseResult.Refused(HostRejection.FORBIDDEN, "role cannot release"),
        )

        for (result in results) {
            assertEquals(result, HandoffWire.decodeReleaseResult(HandoffWire.encode(result)))
        }
    }

    @Test
    fun acquireResultsKeepTheNewHolderOrTheRefusal() {
        val results = listOf(
            AcquireResult.Acquired(generation, holder),
            AcquireResult.Refused(HostRejection.UNAUTHORIZED, "caller is not allowed"),
        )

        for (result in results) {
            assertEquals(result, HandoffWire.decodeAcquireResult(HandoffWire.encode(result)))
        }
    }

    @Test
    fun openResultsKeepTheLeaseAndStatusOrTheRefusal() {
        val results = listOf(
            HostOpenResult.Opened(HostLease("lease-1", ClientRole.MILINK, "incarnation-a"), HostStatus.Active(generation, holder)),
            HostOpenResult.Refused(HostRejection.INVALID_LEASE, "unknown lease"),
        )

        for (result in results) {
            assertEquals(result, HostIdentityWire.decodeOpenResult(HostIdentityWire.encode(result)))
        }
    }

    @Test
    fun aKindFromAnotherVersionIsRejectedInsteadOfGuessed() {
        val foreign = Wire.Obj(mapOf("t" to Wire.Str("teleport")))

        assertFailsWith<WireFormatException> { HostWire.decodeCommand(foreign) }
        assertFailsWith<WireFormatException> { HostWire.decodeCallResult(foreign) }
        assertFailsWith<WireFormatException> { HandoffWire.decodeReleaseResult(foreign) }
        assertFailsWith<WireFormatException> { HandoffWire.decodeAcquireResult(foreign) }
        assertFailsWith<WireFormatException> { HostIdentityWire.decodeOpenResult(foreign) }
        assertFailsWith<WireFormatException> { HostIdentityWire.decodeStatus(foreign) }
    }
}
