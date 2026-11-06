package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.AcquireResult
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.ReleaseReceipt
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What one side of the module-host IPC encodes, the other side decodes to the same value. */
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
            HostCommand.Connect(DeviceId("AA:BB:CC:DD:EE:01")),
            HostCommand.ConnectFromSystem(DeviceId("AA:BB:CC:DD:EE:02")),
            HostCommand.ConnectForAddDevice(DeviceId("AA:BB:CC:DD:EE:03")),
            HostCommand.Disconnect,
            HostCommand.Set(reference, DeviceSetting.GAME_MODE, FieldValue.Toggle(true)),
            HostCommand.Set(null, DeviceSetting.GAME_MODE, FieldValue.Toggle(false)),
            HostCommand.UpdateSettings(HostSettings(integration = IntegrationSettings(islandMode = IslandMode.MODULE))),
            HostCommand.ClaimPrompt(reference, "prompt-1"),
        )

        for (command in commands) {
            assertEquals(command, HostWire.decodeCommand(HostWire.encode(command)))
        }
    }

    @Test
    fun aCommandAddressedToAGenerationKeepsBothParts() {
        val command = HostCommand.ClaimPrompt(reference, "prompt-1")

        assertEquals(generation to command, HostWire.decodeCall(HostWire.encodeCall(generation, command)))
    }

    @Test
    fun anAcquireRequestKeepsTheSwitchAndTheSettingsItCarries() {
        val request = SwitchRequest("switch-1", ControlWay.ORDINARY, ControlWay.MODULE, generation)
        val settings = HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))

        assertEquals(request to settings, HostWire.decodeAcquire(HostWire.encodeAcquire(request, settings)))
    }

    @Test
    fun aSwitchRequestWithoutAnObservedGenerationKeepsThatAbsence() {
        val request = SwitchRequest("switch-2", ControlWay.MODULE, ControlWay.ORDINARY)

        assertEquals(request, HostWire.decodeRequest(HostWire.encode(request)))
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

        assertEquals(anonymous, HostWire.decodeHolder(HostWire.encode(anonymous)))
    }

    @Test
    fun hostStatusKeepsWhoHoldsTheSessionOrWhyNobodyDoes() {
        val statuses = listOf(
            HostStatus.Active(generation, holder),
            HostStatus.Inactive("incarnation-a", generation, "released by a switch request"),
            HostStatus.Inactive("incarnation-b", null, "module disabled"),
        )

        for (status in statuses) {
            assertEquals(status, HostWire.decodeStatus(HostWire.encode(status)))
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
            assertEquals(result, HostWire.decodeReleaseResult(HostWire.encode(result)))
        }
    }

    @Test
    fun acquireResultsKeepTheNewHolderOrTheRefusal() {
        val results = listOf(
            AcquireResult.Acquired(generation, holder),
            AcquireResult.Refused(HostRejection.UNAUTHORIZED, "caller is not allowed"),
        )

        for (result in results) {
            assertEquals(result, HostWire.decodeAcquireResult(HostWire.encode(result)))
        }
    }

    @Test
    fun openResultsKeepTheLeaseAndStatusOrTheRefusal() {
        val results = listOf(
            HostOpenResult.Opened(HostLease("lease-1", ClientRole.MILINK, "incarnation-a"), HostStatus.Active(generation, holder)),
            HostOpenResult.Refused(HostRejection.INVALID_LEASE, "unknown lease"),
        )

        for (result in results) {
            assertEquals(result, HostWire.decodeOpenResult(HostWire.encode(result)))
        }
    }

    @Test
    fun aKindFromAnotherVersionIsRejectedInsteadOfGuessed() {
        val foreign = Wire.Obj(mapOf("t" to Wire.Str("teleport")))

        assertFailsWith<WireFormatException> { HostWire.decodeCommand(foreign) }
        assertFailsWith<WireFormatException> { HostWire.decodeCallResult(foreign) }
        assertFailsWith<WireFormatException> { HostWire.decodeReleaseResult(foreign) }
        assertFailsWith<WireFormatException> { HostWire.decodeAcquireResult(foreign) }
        assertFailsWith<WireFormatException> { HostWire.decodeOpenResult(foreign) }
        assertFailsWith<WireFormatException> { HostWire.decodeStatus(foreign) }
    }
}
