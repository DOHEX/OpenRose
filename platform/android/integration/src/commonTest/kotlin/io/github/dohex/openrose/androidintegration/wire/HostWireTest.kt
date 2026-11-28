package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.AcquireResult
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.ReleaseReceipt
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
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
    fun anOwnerStateKeepsFactsOperationsAndTheTouchLayoutOnTheWire() {
        val device = DeviceState(
            deviceId = reference.deviceId,
            sessionId = reference.sessionId,
            profile = ModelProfile.I5_ZT,
            capabilities = DeviceCapabilities(
                readableFields = persistentSetOf(DeviceField.SPATIAL_AUDIO),
                writableSettings = persistentSetOf(DeviceSetting.SPATIAL_AUDIO),
                touchControl = TouchControlCapabilities(
                    slots = persistentSetOf(TouchSlot("left", "single")),
                    actions = persistentSetOf("play_pause"),
                    slotLabels = persistentMapOf(TouchSlot("left", "single") to "电源键"),
                ),
            ),
            fields = persistentMapOf(DeviceField.SPATIAL_AUDIO to FieldReading.Known(FieldValue.Toggle(false), "00")),
        )
        val operation = SettingOperation(
            id = 12,
            sessionId = reference.sessionId,
            setting = DeviceSetting.SPATIAL_AUDIO,
            requestedValue = FieldValue.Toggle(true),
            stage = SettingStage.WRITTEN,
            confirmedFields = persistentSetOf(DeviceField.SPATIAL_AUDIO),
        )
        val facts = HostPlatformFacts(PermissionState.GRANTED, TransportAvailability.Available, scanPermission = PermissionState.DENIED)
        val state = ControlState(
            platform = PlatformState(PermissionState.NOT_REQUIRED),
            device = device,
            settingOperations = persistentListOf(operation),
            audioDeviceId = reference.deviceId,
            selectedHeadsetId = reference.deviceId,
        )

        val decoded = ControlWire.decodeOwned(ControlWire.encodeOwned(facts, state))
        assertEquals(facts, decoded.platform)
        assertEquals(device, decoded.state.device)
        assertEquals(listOf(operation), decoded.state.settingOperations)
        assertEquals(reference.deviceId, decoded.state.audioDeviceId)
        assertEquals(reference.deviceId, decoded.state.selectedHeadsetId)

        for (transport in listOf(
            TransportAvailability.Unavailable(TransportUnavailableReason.NoAdapter),
            TransportAvailability.Unavailable(TransportUnavailableReason.UnsupportedPlatform("macOS")),
            TransportAvailability.Unavailable(TransportUnavailableReason.MissingDependency("libdbus")),
            TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable("dbus error")),
        )) {
            val unavailableFacts = HostPlatformFacts(PermissionState.GRANTED, transport)
            assertEquals(unavailableFacts, ControlWire.decodeOwned(ControlWire.encodeOwned(unavailableFacts, state)).platform)
        }
    }

    @Test
    fun everyModelsCapabilitiesSurviveTheOwnerWire() {
        val facts = HostPlatformFacts(PermissionState.GRANTED, TransportAvailability.Available)
        for (profile in ModelProfile.entries) {
            val device = DeviceState(
                deviceId = reference.deviceId,
                sessionId = reference.sessionId,
                profile = profile,
                capabilities = profile.capabilities(),
                fields = persistentMapOf(),
            )
            val state = ControlState(platform = PlatformState(PermissionState.NOT_REQUIRED), device = device)
            assertEquals(device, ControlWire.decodeOwned(ControlWire.encodeOwned(facts, state)).state.device, profile.name)
        }
    }

    @Test
    fun anOwnerPayloadMissingAFieldTheEncoderAlwaysWritesIsRejected() {
        val profile = ModelProfile.ASTRO_X_RS_V2
        val device = DeviceState(
            deviceId = reference.deviceId,
            sessionId = reference.sessionId,
            profile = profile,
            capabilities = profile.capabilities(),
            fields = persistentMapOf(),
        )
        val facts = HostPlatformFacts(PermissionState.GRANTED, TransportAvailability.Available, PermissionState.DENIED)
        val state = ControlState(platform = PlatformState(PermissionState.NOT_REQUIRED), device = device)

        val owned = ControlWire.encodeOwned(facts, state) as Wire.Obj
        assertFailsWith<WireFormatException> { ControlWire.decodeOwned(Wire.Obj(owned.fields - "scanPermission")) }

        val capabilities = encode(device.capabilities) as Wire.Obj
        assertEquals(device.capabilities, decodeCapabilities(capabilities))
        for (key in listOf("spatialModes", "spatialContents")) {
            assertFailsWith<WireFormatException> { decodeCapabilities(Wire.Obj(capabilities.fields - key)) }
        }
    }

    @Test
    fun aConfirmedBatteryOutsideZeroToOneHundredIsAMalformedFrame() {
        val profile = ModelProfile.I5_ZT
        fun device(level: Int) = DeviceState(
            deviceId = reference.deviceId,
            sessionId = reference.sessionId,
            profile = profile,
            capabilities = profile.capabilities(),
            fields = persistentMapOf(DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(level), "ff")),
        )

        assertEquals(device(100), decodeDevice(encode(device(100))))
        assertFailsWith<WireFormatException> { decodeDevice(encode(device(255))) }
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

        assertFailsWith<WireFormatException> { HostWire.decodeCallResult(foreign) }
        assertFailsWith<WireFormatException> { HandoffWire.decodeReleaseResult(foreign) }
        assertFailsWith<WireFormatException> { HandoffWire.decodeAcquireResult(foreign) }
        assertFailsWith<WireFormatException> { HostIdentityWire.decodeOpenResult(foreign) }
        assertFailsWith<WireFormatException> { HostIdentityWire.decodeStatus(foreign) }
    }
}
