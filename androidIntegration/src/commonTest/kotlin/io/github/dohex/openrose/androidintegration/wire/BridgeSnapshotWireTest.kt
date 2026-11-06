package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the host encodes is what the system-side reader parses; anything incomplete or foreign is rejected whole. */
class BridgeSnapshotWireTest {
    @Test
    fun anI5SnapshotSurvivesTheWireWithItsAddressesCapabilitiesAndReadableValues() {
        val wire = i5State().toBridgeSnapshot(HOST_EPOCH, readSequence = 4, capturedElapsedMillis = CAPTURED)

        val snapshot = requireNotNull(NativeHeadsetSnapshot.fromWire(wire.asWireMap()))

        assertEquals(NativeSessionReference(HOST_EPOCH, "aa:bb:cc:dd:ee:01", 7), snapshot.reference)
        assertEquals(setOf("AA:BB:CC:DD:EE:01"), snapshot.addresses)
        assertEquals(ModelProfile.I5_ZT, snapshot.profile)
        assertEquals(setOf("NOISE_MODE", "ANC_LEVEL"), snapshot.writableSettings)
        assertEquals(setOf("anc", "normal", "transparency"), snapshot.noiseModes)
        assertEquals(NativeNoiseLevelCapabilities(setOf(1, 3, 5), setOf("anc")), snapshot.ancLevel)
        assertNull(snapshot.transparencyLevel)
        assertEquals(
            mapOf(
                DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(80), ""),
                DeviceField.LEFT_CHARGING to FieldReading.Known(FieldValue.Toggle(true), ""),
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), ""),
                DeviceField.RIGHT_BATTERY to FieldReading.Loading,
                DeviceField.CASE_BATTERY to FieldReading.NotProvided(""),
                // Values the system side cannot present are passed on as unknown, never as a guess.
                DeviceField.CUSTOM_EQ to FieldReading.Unknown(""),
            ),
            snapshot.fields,
        )
        assertTrue(snapshot.supportsNoise("anc"))
        assertFalse(snapshot.supportsNoise("wind"))
        assertTrue(snapshot.matchesAddress(" aa:bb:cc:dd:ee:01 "))
    }

    @Test
    fun anOpaqueDeviceIdIsNeverTreatedAsAnAddressButTheClassicAddressIs() {
        val state = i5State().copy(deviceId = DeviceId("rose-a"), profile = ModelProfile.I7_RS_V2, classicAddress = "11:22:33:44:55:66")

        val snapshot = requireNotNull(NativeHeadsetSnapshot.fromWire(state.toBridgeSnapshot(HOST_EPOCH, 1, CAPTURED).asWireMap()))

        assertEquals("rose-a", snapshot.deviceId)
        assertEquals(setOf("11:22:33:44:55:66"), snapshot.addresses)
    }

    @Test
    fun incompleteForeignOrMistypedSnapshotsAreRejected() {
        val valid = i5State().toBridgeSnapshot(HOST_EPOCH, 1, CAPTURED)
        val rejected = mapOf(
            "foreign version" to valid + (BridgeWire.KEY_VERSION to BridgeWire.VERSION + 1),
            "no capture time" to valid - "captured_elapsed_millis",
            "unknown model" to valid + ("profile" to "I9"),
            "no address" to valid - "device_address" + (BridgeWire.DEVICE_ID to "rose-a"),
            "blank host epoch" to valid + (BridgeWire.HOST_EPOCH to " "),
            // A Bundle keeps an Int as an Int; reading it as the session id must fail rather than coerce.
            "session id as Int" to valid + (BridgeWire.SESSION_ID to 7),
            "no capabilities" to valid - "capabilities",
        )
        rejected.forEach { (case, wire) -> assertNull(NativeHeadsetSnapshot.fromWire(wire.asWireMap()), case) }
        assertNull(NativeHeadsetSnapshot.fromWire(null))
    }

    @Test
    fun aSnapshotIsFreshFromCaptureForThreeSecondsAndNeverBeforeIt() {
        val snapshot = requireNotNull(NativeHeadsetSnapshot.fromWire(i5State().toBridgeSnapshot(HOST_EPOCH, 1, CAPTURED).asWireMap()))

        assertFalse(snapshot.isFresh(CAPTURED - 1))
        assertTrue(snapshot.isFresh(CAPTURED))
        assertTrue(snapshot.isFresh(CAPTURED + NativeHeadsetSnapshot.MAX_AGE_MILLIS))
        assertFalse(snapshot.isFresh(CAPTURED + NativeHeadsetSnapshot.MAX_AGE_MILLIS + 1))
    }

    @Test
    fun aMapViewReadsAbsentOrMistypedValuesAsNullAndKeepsOnlyListElementsOfTheAskedType() {
        val wire = mapOf<String, Any?>(
            "text" to "a",
            "number" to 1,
            "mixed" to listOf("x", 2, "y", 3),
            "child" to mapOf("inner" to true),
        ).asWireMap()

        assertNull(wire.int("text"))
        assertNull(wire.long("number"))
        assertNull(wire.string("absent"))
        assertNull(wire.child("text"))
        assertEquals(listOf("x", "y"), wire.strings("mixed"))
        assertEquals(listOf(2, 3), wire.ints("mixed"))
        assertEquals(true, wire.child("child")?.boolean("inner"))
    }

    private fun i5State() = DeviceState(
        deviceId = DeviceId("aa:bb:cc:dd:ee:01"),
        sessionId = 7,
        profile = ModelProfile.I5_ZT,
        capabilities = DeviceCapabilities(
            readableFields = persistentSetOf(DeviceField.LEFT_BATTERY, DeviceField.NOISE_MODE),
            writableSettings = persistentSetOf(DeviceSetting.NOISE_MODE, DeviceSetting.ANC_LEVEL),
            noiseControl = NoiseControlCapabilities(
                modes = persistentSetOf("anc", "normal", "transparency"),
                cycleModes = persistentSetOf("anc", "normal"),
                ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            ),
        ),
        fields = persistentMapOf(
            DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(80), "50"),
            DeviceField.LEFT_CHARGING to FieldReading.Known(FieldValue.Toggle(true), "01"),
            DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "01000000"),
            DeviceField.RIGHT_BATTERY to FieldReading.Loading,
            DeviceField.CASE_BATTERY to FieldReading.NotProvided("ff"),
            DeviceField.CUSTOM_EQ to FieldReading.Known(FieldValue.IntegerList(persistentListOf(1, 2)), "0102"),
        ),
    )

    private companion object {
        const val HOST_EPOCH = "epoch-1"
        const val CAPTURED = 10_000L
    }
}
