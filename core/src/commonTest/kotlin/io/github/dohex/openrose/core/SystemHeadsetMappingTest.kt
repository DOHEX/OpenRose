package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What the system's own headset UIs may show, derived from the readings a MiLink lease actually receives
 * from the module host over real earphone replies. The earphones' "not provided" and unknown readings
 * must never become real numbers, charging or wear state.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemHeadsetMappingTest {
    private suspend fun TestScope.readAsMiLink(profile: ModelProfile = ModelProfile.I5_ZT): Map<DeviceField, FieldReading> {
        val rig = OwnerRig(this, earphones = rosePair("rose-a", profile), initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.bootHost()
        runCurrent()
        testScheduler.advanceUntilIdle()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(milinkIdentity) as HostOpenResult.Opened).lease.id
        val view = requireNotNull(rig.host.view(milinkIdentity, lease))
        val connection = assertIs<ConnectionState.Connected>(view.state.connection)
        val read = rig.host.readSession(milinkIdentity, lease, generation, SessionReference(connection.deviceId, connection.sessionId))
        val snapshot = assertIs<SessionSnapshotResult.Available>(assertIs<HostReadResult.Available>(read).snapshot)
        return snapshot.device.fields
    }

    @Test
    fun anI5BoxBatteryOfFFIsNeverShownAsARealReadingToMiLink() = runTest {
        val fields = readAsMiLink()

        assertEquals(FieldReading.NotProvided("ff"), fields[DeviceField.CASE_BATTERY])
        // MiLink: [case, left, right, caseCharging, leftCharging, rightCharging]; -1 is its own "unavailable".
        assertEquals(listOf(-1, 100, 98, 0, 0, 0), SystemHeadsetMapping.miLinkBattery(fields))
        assertEquals(98, SystemHeadsetMapping.miLinkSummaryBattery(fields))
    }

    @Test
    fun theRealSystemNoiseModeIsMappedAndWindReducesToTheOffSelection() = runTest {
        val fields = readAsMiLink()

        assertEquals(FieldValue.Text("wind"), (fields[DeviceField.NOISE_MODE] as FieldReading.Known).value)
        assertEquals(0, SystemHeadsetMapping.miLinkAncState(fields))
        assertEquals(0, SystemHeadsetMapping.miLinkMode("normal"))
        assertEquals(1, SystemHeadsetMapping.miLinkMode("anc"))
        assertEquals(2, SystemHeadsetMapping.miLinkMode("transparency"))
        assertNull(SystemHeadsetMapping.miLinkMode("auto"))
        assertEquals("anc", SystemHeadsetMapping.noiseModeFromSystem(1))
        assertNull(SystemHeadsetMapping.noiseModeFromSystem(3))
    }

    @Test
    fun unknownAndMissingReadingsAreNotFabricatedAsBatteryOrMode() {
        val unknownEars = mapOf(
            DeviceField.LEFT_BATTERY to FieldReading.Unknown("00"),
            DeviceField.RIGHT_BATTERY to FieldReading.NotProvided("ff"),
            DeviceField.NOISE_MODE to FieldReading.Unknown("00"),
        )
        assertNull(SystemHeadsetMapping.miLinkBattery(unknownEars))
        assertNull(SystemHeadsetMapping.miLinkSummaryBattery(unknownEars))
        assertNull(SystemHeadsetMapping.miLinkAncState(unknownEars))
        assertNull(SystemHeadsetMapping.miLinkAncState(emptyMap()))

        val oneEar = mapOf(DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(40), "28"))
        assertEquals(listOf(-1, 40, -1, 0, 0, 0), SystemHeadsetMapping.miLinkBattery(oneEar))
        assertNull(SystemHeadsetMapping.miLinkSummaryBattery(oneEar))

        val outOfRange = mapOf(
            DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(101), "65"),
            DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(-3), "fd"),
        )
        assertNull(SystemHeadsetMapping.miLinkBattery(outOfRange))
    }

    @Test
    fun unknownChargingIsNotReportedAsChargingOrAsNotCharging() {
        val chargingUnknown = mapOf(
            DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(60), "3c"),
            DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(61), "3d"),
            DeviceField.LEFT_CHARGING to FieldReading.Unknown("02"),
        )
        // MiLink's list has no unknown slot: 0 is its default and is not claimed as a reading.
        assertEquals(listOf(-1, 60, 61, 0, 0, 0), SystemHeadsetMapping.miLinkBattery(chargingUnknown))
        // The headset Binder encodes charging in the battery byte, so an unknown flag cannot be sent at all.
        assertEquals(SystemHeadsetMapping.BinderStatus.Unrepresentable, SystemHeadsetMapping.binderStatus(chargingUnknown))
    }

    @Test
    fun theBinderStatusSendsTheRealEarBatteriesAndUnknownCaseWithoutWearOrModeGuesses() = runTest {
        val fields = readAsMiLink()

        val status = assertIs<SystemHeadsetMapping.BinderStatus.Payload>(SystemHeadsetMapping.binderStatus(fields))
        // left, right, case(255 = not provided), then 13 empty positions; index 7 (mode) and 8 (wear) stay empty.
        assertEquals("100,98,255,,,,,,,,,,,,,", status.text)
        assertEquals(15, status.text.count { it == ',' })
    }

    @Test
    fun aKnownCaseBatteryIsOmittedBecauseCaseChargingIsNotRepresentable() {
        val fields = mapOf(
            DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(90), "5a"),
            DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(80), "50"),
            DeviceField.LEFT_CHARGING to FieldReading.Known(FieldValue.Toggle(true), "01"),
            DeviceField.RIGHT_CHARGING to FieldReading.Known(FieldValue.Toggle(false), "00"),
            DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(70), "46"),
            DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("transparency"), "x"),
        )

        val status = assertIs<SystemHeadsetMapping.BinderStatus.Payload>(SystemHeadsetMapping.binderStatus(fields))
        assertEquals("${90 or 128},80,,,,,,0200,,,,,,,,", status.text)
        assertEquals(true, status.caseOmitted)
    }

    @Test
    fun noEarBatteryAtAllIsNotAStatusPayload() {
        val fields = mapOf(DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "x"))
        assertEquals(SystemHeadsetMapping.BinderStatus.Unrepresentable, SystemHeadsetMapping.binderStatus(fields))
    }

    @Test
    fun everyRefusalOfANoiseCommandStaysDistinguishable() {
        fun report(status: String?) = SystemHeadsetMapping.commandReport("anc", status, null).status
        assertEquals("CONFIRMED", report("OK"))
        assertEquals("REJECTED", report("UNAUTHORIZED"))
        assertEquals("UNSUPPORTED", report("UNSUPPORTED"))
        for (failed in listOf("TIMEOUT", "DISCONNECTED", "STALE_HOST", "STALE_SESSION", "NOT_APPLIED", null)) {
            assertEquals("ACTION_FAILED", report(failed), failed)
        }
        assertTrue(SystemHeadsetMapping.commandReport("anc", "TIMEOUT", null).detail.contains("in time"))
    }
}
