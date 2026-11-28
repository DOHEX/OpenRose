package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeHeadsetDirectoryTest {
    private val ble = "AA:BB:CC:DD:EE:01"
    private val classic = "11:22:33:44:55:66"
    private val other = "66:55:44:33:22:11"
    private var now = 10_000L
    private val directory = NativeHeadsetDirectory { now }

    private fun session() = NativeHeadsetSnapshot(
        hostEpoch = "epoch",
        deviceId = ble,
        sessionId = 7,
        addresses = setOf(ble, classic),
        profile = ModelProfile.I5_ZT,
        capturedElapsedMillis = now,
        writableSettings = emptySet(),
        readableFields = emptySet(),
        noiseModes = emptySet(),
        ancLevel = null,
        transparencyLevel = null,
        fields = emptyMap(),
    )

    @Test
    fun withoutASessionBondedRoseEarphonesAreIdentifiedWhileTheirStateStaysWithTheSystem() {
        directory.publishBonded(setOf(classic))

        assertTrue(directory.identifies(classic.lowercase()))
        assertNull(directory.session(classic))
        assertFalse(directory.identifies(other))
        assertFalse(directory.identifies("not an address"))
    }

    @Test
    fun onceTheModuleHostPublishesNoEarphonesNothingIsIdentified() {
        directory.publishBonded(setOf(classic))
        directory.publishBonded(emptySet())

        assertFalse(directory.identifies(classic))
    }

    @Test
    fun aCurrentSessionAnswersIdentityAndStateOnlyWhileFresh() {
        val current = session()
        directory.publishSession(current)

        assertEquals(current, directory.session(classic.lowercase()))
        assertTrue(directory.identifies(ble))

        now += NativeHeadsetSnapshot.MAX_AGE_MILLIS + 1
        assertNull(directory.session(classic))
        assertFalse(directory.identifies(ble))
    }

    @Test
    fun everyAddressEitherSourceNamedStaysManaged() {
        directory.publishSession(session())
        directory.publishSession(null)
        directory.publishBonded(setOf(other))
        directory.publishBonded(emptySet())

        assertTrue(directory.isManaged(" aa:bb:cc:dd:ee:01"))
        assertTrue(directory.isManaged(classic))
        assertTrue(directory.isManaged(other))
        assertFalse(directory.isManaged("12:34:56:78:9A:BC"))
        assertFalse(directory.isManaged(null))
    }
}
