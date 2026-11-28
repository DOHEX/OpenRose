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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
            val decoded = assertIs<CoreDecodeResult.Ok<HostCommand>>(HostWire.decodeCommand(HostWire.encode(command)))
            assertEquals(command, decoded.value)
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
            val decoded = assertIs<CoreDecodeResult.Ok<HostCommand>>(HostWire.decodeCommand(HostWire.encode(expected)))
            assertEquals(expected, decoded.value)
        }
    }

    @Test
    fun malformedOrUnsupportedCoreCommandBytesAreRejectedWithTypedErrors() {
        assertIs<CoreDecodeResult.Error.Malformed>(HostWire.decodeCommand("corrupt".encodeToByteArray()))
        val wrongVersion = HostWire.encode(HostCommand.StartScan).apply {
            this[3] = (coreWireFormatVersion().toInt() + 1).toByte()
        }
        val versionError = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(HostWire.decodeCommand(wrongVersion))
        assertEquals(coreWireFormatVersion(), versionError.expected)
        assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), versionError.actual)
    }

    @Test
    fun aHostFrameCarriesItsSnapshotAsCoreBytes() {
        val snapshot = testSnapshot()
        val frame = HostFrame(generation, holder, snapshot, SystemPresentationState(), HostFacts(presentation = mapOf("k" to "v")))

        val encoded = HostFrameWire.encode(frame)
        assertEquals(frame, HostFrameWire.decodeFrame(EncodedHostFrame(parseWire(encoded.payload.render()), encoded.snapshot)))
        assertEquals(snapshot.encodeToBytes().toList(), encoded.snapshot.toList())
    }

    @Test
    fun malformedAndUnsupportedSnapshotBytesFailWithATypedException() {
        val snapshot = testSnapshot()
        val encoded = HostFrameWire.encode(HostFrame(generation, holder, snapshot, SystemPresentationState()))

        val malformedError = assertFailsWith<HostSnapshotFormatException> {
            HostFrameWire.decodeFrame(EncodedHostFrame(encoded.payload, "corrupt".encodeToByteArray()))
        }
        assertIs<CoreDecodeResult.Error.Malformed>(malformedError.error)

        val wrongVersionBytes = snapshot.encodeToBytes().apply {
            this[3] = (coreWireFormatVersion().toInt() + 1).toByte()
        }
        val versionError = assertFailsWith<HostSnapshotFormatException> {
            HostFrameWire.decodeFrame(EncodedHostFrame(encoded.payload, wrongVersionBytes))
        }
        val unsupported = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(versionError.error)
        assertEquals(coreWireFormatVersion(), unsupported.expected)
        assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), unsupported.actual)
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

        assertFailsWith<WireFormatException> { HostWire.decodeCallResult(foreign) }
        assertFailsWith<WireFormatException> { HandoffWire.decodeReleaseResult(foreign) }
        assertFailsWith<WireFormatException> { HandoffWire.decodeAcquireResult(foreign) }
        assertFailsWith<WireFormatException> { HostIdentityWire.decodeOpenResult(foreign) }
        assertFailsWith<WireFormatException> { HostIdentityWire.decodeStatus(foreign) }
    }
}
