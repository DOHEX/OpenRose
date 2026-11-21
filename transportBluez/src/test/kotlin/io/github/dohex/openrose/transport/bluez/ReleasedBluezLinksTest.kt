package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReleasedBluezLinksTest {
    @Test
    fun anLeReconnectionAfterTheReleaseReceiptIsDroppedOnlyOnTheReleasedPath() = runTest {
        val bus = FakeBluezBus()
        val guard = ReleasedBluezLinks()
        val dropped = mutableListOf<String>()
        bus.onLeDisconnect = { dropped += it }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { guard.observe(bus) }
        bus.snapshot = objects(le = true, classic = true) + media
        bus.onAudioDisconnect = { bus.snapshot = objects() }
        BluezAudioProfiles(guard) { bus }.release(ADDRESS)
        delay(2_600)
        bus.snapshot = objects(le = true)
        bus.events.emit(BluezSignal.Changed(OTHER, LE_BEARER, mapOf("Connected" to true)))
        bus.events.emit(BluezSignal.Changed(TARGET, LE_BEARER, mapOf("Connected" to true)))
        runCurrent()
        assertEquals(listOf(TARGET), dropped)
    }

    @Test
    fun anExplicitLocalConnectionWaitsForTheInFlightLeDisconnectAndStopsFurtherReleases() = runTest {
        val bus = FakeBluezBus()
        val guard = ReleasedBluezLinks()
        val disconnecting = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val dropped = mutableListOf<String>()
        bus.onLeDisconnect = {
            dropped += it
            disconnecting.complete(Unit)
            closed.await()
            bus.snapshot = objects()
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { guard.observe(bus) }
        bus.snapshot = objects()
        guard.release(bus, listOf(TARGET)) {}
        bus.snapshot = objects(le = true)
        bus.events.emit(BluezSignal.Changed(TARGET, LE_BEARER, mapOf("Connected" to true)))
        runCurrent()
        assertTrue(disconnecting.isCompleted)
        bus.onAudioConnect = { bus.snapshot += media }
        bus.onConnect = { bus.snapshot = objects(le = true) }
        val resumed = async(start = CoroutineStart.UNDISPATCHED) { BluezAudioProfiles(guard) { bus }.acquire(ADDRESS) }
        assertFalse(resumed.isCompleted)
        closed.complete(Unit)
        resumed.await()
        assertEquals(listOf(true), bus.connectBearers, "Local acquisition must see the result of the old LE disconnect")
        bus.snapshot = objects(le = true)
        bus.events.emit(BluezSignal.Changed(TARGET, LE_BEARER, mapOf("Connected" to true)))
        runCurrent()
        assertEquals(listOf(TARGET), dropped)
    }

    @Test
    fun aDelayedClassicAndMediaSnapshotCannotDisarmAReleaseBeforeItsReceipt() = runTest {
        val bus = FakeBluezBus()
        val guard = ReleasedBluezLinks()
        val dropped = mutableListOf<String>()
        bus.onLeDisconnect = {
            dropped += it
            bus.snapshot = objects()
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { guard.observe(bus) }
        bus.snapshot = objects(le = true, classic = true) + media
        guard.release(bus, listOf(TARGET)) { bus.snapshot = objects() }
        bus.events.emit(BluezSignal.Changed(TARGET, CLASSIC_BEARER, mapOf("Connected" to true)))
        bus.events.emit(BluezSignal.Added(media.first, media.second))
        runCurrent()
        delay(2_600)
        bus.snapshot = objects(le = true)
        bus.events.emit(BluezSignal.Changed(TARGET, LE_BEARER, mapOf("Connected" to true)))
        runCurrent()
        assertEquals(listOf(TARGET), dropped)
    }

    @Test
    fun anLeConnectionBetweenTheLastConfirmationSnapshotAndEnrollmentIsReleased() = runTest {
        val bus = FakeBluezBus()
        val guard = ReleasedBluezLinks()
        val dropped = mutableListOf<String>()
        bus.snapshot = objects()
        bus.onLeDisconnect = {
            dropped += it
            bus.snapshot = objects()
        }
        guard.release(bus, listOf(TARGET)) { bus.snapshot = objects(le = true) }
        assertEquals(listOf(TARGET), dropped)
    }

    @Test
    fun classicRecoveryMediaOrLossOfTheDeviceAdapterOrOwnerEndsTheRelease() = runTest {
        val cases = listOf(
            BluezSignal.Changed(TARGET, CLASSIC_BEARER, mapOf("Connected" to true)) to objects(le = true, classic = true),
            BluezSignal.Added(media.first, media.second) to (objects(le = true) + media),
            BluezSignal.Removed(TARGET, setOf(DEVICE)) to (objects() - TARGET),
            BluezSignal.Removed(ADAPTER_PATH, setOf(ADAPTER)) to emptyMap(),
            BluezSignal.Changed(ADAPTER_PATH, ADAPTER, mapOf("Powered" to false)) to emptyMap(),
            BluezSignal.Owner(false) to emptyMap(),
        )
        for ((signal, snapshot) in cases) {
            val bus = FakeBluezBus()
            val guard = ReleasedBluezLinks()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { guard.observe(bus) }
            bus.snapshot = objects()
            guard.release(bus, listOf(TARGET)) {}
            bus.onLeDisconnect = { error("Unexpected LE disconnect after $signal") }
            bus.snapshot = snapshot
            bus.events.emit(signal)
            runCurrent()
            bus.snapshot = objects(le = true)
            bus.events.emit(BluezSignal.Changed(TARGET, LE_BEARER, mapOf("Connected" to true)))
            runCurrent()
            observer.cancel()
        }
    }

    private val media = "$TARGET/sep1/fd0" to mapOf(MEDIA_TRANSPORT to mapOf("Device" to TARGET, "UUID" to AUDIO_SINK_UUID))

    private fun objects(le: Boolean = false, classic: Boolean = false): BluezObjects = mapOf(
        ADAPTER_PATH to mapOf(ADAPTER to mapOf("Powered" to true)),
        TARGET to mapOf(
            DEVICE to mapOf("Paired" to true, "Address" to ADDRESS),
            CLASSIC_BEARER to mapOf("Connected" to classic),
            LE_BEARER to mapOf("Connected" to le),
        ),
        OTHER to mapOf(
            DEVICE to mapOf("Paired" to true, "Address" to "00:11:22:33:44:55"),
            CLASSIC_BEARER to mapOf("Connected" to false),
            LE_BEARER to mapOf("Connected" to true),
        ),
    )

    private companion object {
        const val ADAPTER_PATH = "/org/bluez/hci0"
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val TARGET = "$ADAPTER_PATH/dev_AA_BB_CC_DD_EE_FF"
        const val OTHER = "$ADAPTER_PATH/dev_00_11_22_33_44_55"
    }
}
