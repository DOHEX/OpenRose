package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.freedesktop.dbus.types.UInt16
import kotlin.test.*

class BluezControlTransportTest {
    @Test
    fun discoverySnapshotFailureKeepsItsReasonWhenCleanupAlsoFails() = runBlocking {
        val bus = FakeBluezBus().also {
            it.snapshot = baseObjects()
            it.stopDiscoveryFailure = IllegalStateException("cleanup failed")
            it.onStartDiscovery = { it.objectsFailure = IllegalStateException("advertisement read failed") }
        }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        val failure = assertFailsWith<IllegalStateException> { transport.scan().first() }
        assertEquals("advertisement read failed", failure.message)
        val cleanupFailures = generateSequence<Throwable>(failure) { it.cause }.flatMap { it.suppressed.asSequence() }
        assertTrue(cleanupFailures.any { it.message == "cleanup failed" })
        transport.close()
    }

    @Test
    fun deniedDiscoveryMarksTransportUnavailable() = runBlocking {
        for (denied in listOf(
            org.freedesktop.dbus.errors.AccessDenied("denied"),
            org.bluez.Error.NotPermitted("denied"),
            org.bluez.Error.NotAuthorized("denied"),
        )) {
            val bus = FakeBluezBus().also {
                it.snapshot = baseObjects()
                it.discoveryFailure = denied
            }
            val transport = BluezControlTransport(this, bus)
            transport.awaitInitialization()
            assertEquals(denied::class, assertFails { transport.scan().first() }::class)
            assertEquals(TransportAvailability.Unavailable("D-Bus 拒绝访问蓝牙"), transport.availability.value)
            assertEquals(0, bus.scanStops)
            transport.close()
        }
    }

    @Test
    fun existingAudioStartsControlWithALateGattClientAndReleasesOnRemoval() = runBlocking {
        val service = "$DEVICE_PATH/service0001"
        val writer = "$service/char0001"
        val notifier = "$service/char0002"
        val bus = FakeBluezBus().also {
            it.snapshot = baseObjects() + mapOf(
                DEVICE_PATH to mapOf(DEVICE to mapOf("Address" to "00:11:22:33:44:55", "Name" to "EarFree i5", "Paired" to true, "Connected" to true, "ServicesResolved" to true)),
                MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID, "State" to "idle")),
                service to mapOf(SERVICE to mapOf("UUID" to "011bf5da-0000-1000-8000-00805f9b34fb")),
                writer to mapOf(CHARACTERISTIC to mapOf("UUID" to "00007777-0000-1000-8000-00805f9b34fb", "Flags" to listOf("write"))),
                notifier to mapOf(CHARACTERISTIC to mapOf("UUID" to "00008888-0000-1000-8000-00805f9b34fb", "Flags" to listOf("notify"))),
            )
        }
        val released = CompletableDeferred<String>()
        bus.onStopNotify = { released.complete(it) }
        var firstWriteRejections = 16 // 2 s at the BlueZ driver's 125 ms retry interval.
        bus.onWrite = { bytes ->
            if (firstWriteRejections > 0) {
                firstWriteRejections--
                throw org.bluez.Error.Failed("Not connected")
            }
            val command = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }.substring(10, 14)
            i5Replies[command]?.let { hex -> bus.events.emit(BluezSignal.Changed(notifier, CHARACTERISTIC, mapOf("Value" to hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = BluezControlTransport(scope, bus)
        transport.awaitInitialization()
        val initial = PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Unavailable("initializing"))
        val runtime = LocalControlRuntime(initial, transport = transport, scope = scope, timing = SessionTiming(writeIntervalMillis = 0))
        withTimeout(5_000) { runtime.state.first { it.bondedDevices.isNotEmpty() } }
        withTimeout(5_000) { runtime.state.first { it.lastError?.code == ControlErrorCode.TRANSPORT_UNAVAILABLE } }
        val availability = scope.launch { transport.availability.collect { runtime.updatePlatformState(initial.copy(transport = it)) } }
        withTimeout(8_000) { runtime.state.first { (it.connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY } }
        assertEquals(1, bus.connects)
        bus.snapshot = bus.snapshot - MEDIA_PATH
        bus.events.emit(BluezSignal.Removed(MEDIA_PATH, setOf(MEDIA_TRANSPORT)))
        withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Disconnected } }
        assertEquals(notifier, withTimeout(5_000) { released.await() })
        availability.cancelAndJoin()
        transport.close()
        scope.cancel()
    }

    @Test
    fun scanConvertsManufacturerDataAndReleasesDiscovery() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = baseObjects() }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        assertEquals(TransportAvailability.Available, transport.availability.value)
        val result = withTimeout(5_000) { transport.scan().first() }
        assertEquals(DeviceId("00:11:22:33:44:55"), result.deviceId)
        assertContentEquals(byteArrayOf(1, 2, 3), result.manufacturerData[0x1234])
        assertEquals(1, bus.scans)
        assertEquals(1, bus.scanStops)
        transport.close()
    }

    @Test
    fun uuidAndGattConnectionAloneDoNotStartAudioButMediaTransportDoesAndRemovalEndsIt() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = baseObjects() }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        val events = Channel<DeviceId?>(Channel.UNLIMITED)
        val listener = launch { transport.audioConnectedDevice.collect { events.send(it) } }
        assertNull(withTimeout(5_000) { events.receive() })
        bus.snapshot = baseObjects() + (MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID, "State" to "idle")))
        bus.events.emit(BluezSignal.Added(MEDIA_PATH, bus.snapshot.getValue(MEDIA_PATH)))
        assertEquals(DeviceId("00:11:22:33:44:55"), withTimeout(5_000) { events.receive() })
        bus.snapshot = baseObjects() - DEVICE_PATH
        bus.events.emit(BluezSignal.Removed(DEVICE_PATH, setOf(DEVICE)))
        assertNull(withTimeout(5_000) { events.receive() })
        listener.cancelAndJoin()
        transport.close()
    }

    @Test
    fun resumingWithTheSameAudioTransportReestablishesCurrentAudio() = runBlocking {
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID)))
        }
        val transport = BluezControlTransport(this, bus)
        val seen = Channel<DeviceId?>(Channel.UNLIMITED)
        val listener = launch { transport.audioConnectedDevice.collect { seen.send(it) } }
        try {
            val id = DeviceId("00:11:22:33:44:55")
            assertEquals(id, withTimeout(5_000) { seen.receive() })
            bus.events.emit(BluezSignal.PrepareForSleep(true))
            assertNull(withTimeout(5_000) { seen.receive() })
            bus.events.emit(BluezSignal.PrepareForSleep(false))
            assertEquals(id, withTimeout(5_000) { seen.receive() })
        } finally {
            listener.cancelAndJoin()
            transport.close()
        }
    }

    @Test
    fun discoveryMergesDeviceDeltasWithoutReadingTheObjectTreeAgain() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = baseObjects() }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        val seen = Channel<Advertisement>(Channel.BUFFERED)
        val scan = launch { transport.scan().collect { seen.send(it) } }
        try {
            val initial = withTimeout(5_000) { seen.receive() }
            bus.objectsFailure = IllegalStateException("unexpected object tree read")
            bus.events.emit(BluezSignal.Changed(DEVICE_PATH, DEVICE, mapOf("RSSI" to -43)))
            val changed = withTimeout(5_000) { seen.receive() }
            assertEquals(-43, changed.rssi)
            assertEquals(initial.deviceId, changed.deviceId)
            assertContentEquals(initial.manufacturerData[0x1234], changed.manufacturerData[0x1234])

            bus.events.emit(BluezSignal.Removed(DEVICE_PATH, setOf(DEVICE)))
            val added = mapOf(DEVICE to mapOf("Address" to initial.deviceId.value, "Alias" to "new name"))
            bus.events.emit(BluezSignal.Added(DEVICE_PATH, added))
            val replacement = withTimeout(5_000) { seen.receive() }
            assertEquals("new name", replacement.name)
            assertNull(replacement.rssi)
            assertTrue(replacement.manufacturerData.isEmpty())
        } finally {
            scan.cancelAndJoin()
            transport.close()
        }
    }

    @Test
    fun poweringOffASpareAdapterKeepsAudioObservationOnTheOtherAdapter() = runBlocking {
        val spare = "/org/bluez/hci1"
        val media = mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID))
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (spare to mapOf(ADAPTER to mapOf("Powered" to true)))
        }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        val seen = Channel<DeviceId?>(Channel.BUFFERED)
        val listener = launch { transport.audioConnectedDevice.collect { seen.send(it) } }
        try {
            assertNull(withTimeout(5_000) { seen.receive() })
            bus.snapshot += spare to mapOf(ADAPTER to mapOf("Powered" to false))
            bus.events.emit(BluezSignal.Changed(spare, ADAPTER, mapOf("Powered" to false)))
            bus.snapshot += MEDIA_PATH to media
            bus.events.emit(BluezSignal.Added(MEDIA_PATH, media))
            assertEquals(DeviceId("00:11:22:33:44:55"), withTimeout(5_000) { seen.receive() })
        } finally {
            listener.cancelAndJoin()
            transport.close()
        }
    }

    @Test
    fun scanAndControlIgnoreThePoweredOffAdaptersCopyOfTheHeadset() = runBlocking {
        val usb = "/org/bluez/hci1"
        val headset = "$usb/${DEVICE_PATH.substringAfterLast('/')}"
        val service = "$headset/service0001"
        val characteristic = "$service/char0001"
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + mapOf(
                ADAPTER_PATH to mapOf(ADAPTER to mapOf("Powered" to false)),
                usb to mapOf(ADAPTER to mapOf("Powered" to true)),
                headset to mapOf(DEVICE to baseObjects().getValue(DEVICE_PATH).getValue(DEVICE) + mapOf("RSSI" to -42, "ServicesResolved" to true)),
                service to mapOf(SERVICE to mapOf("UUID" to "test-service")),
                characteristic to mapOf(CHARACTERISTIC to mapOf("UUID" to "test-char", "Flags" to listOf("write", "notify"))),
            )
        }
        val transport = BluezControlTransport(this, bus)
        val results = Channel<Advertisement>(Channel.BUFFERED)
        val scan = launch { transport.scan().collect { results.send(it) } }
        try {
            val candidate = withTimeout(2_000) { results.receive() }
            assertEquals(-42, candidate.rssi)
            bus.events.emit(BluezSignal.Changed(DEVICE_PATH, DEVICE, mapOf("RSSI" to -66)))
            bus.events.emit(BluezSignal.Changed(headset, DEVICE, mapOf("RSSI" to -50)))
            assertEquals(-50, withTimeout(2_000) { results.receive() }.rssi)
            scan.cancelAndJoin()
            assertEquals(candidate.deviceId, transport.bondedDevices().single().deviceId)
            val endpoint = TransportEndpoint.BleGatt("test-service", "test-char", "test-char")
            val link = withTimeout(2_000) { transport.connect(candidate.deviceId, endpoint) {} }
            link.close()
        } finally {
            scan.cancelAndJoin()
            transport.close()
        }
    }

    @Test
    fun mediaOwnershipUsesTheDeclaredDeviceOrItsPathWhenThePropertyIsMissing() {
        val other = "/org/bluez/hci1/${DEVICE_PATH.substringAfterLast('/')}"
        val explicitOwner = mapOf(MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to other)))
        assertTrue(explicitOwner.hasMediaTransport(other))
        assertFalse(explicitOwner.hasMediaTransport(DEVICE_PATH))

        val implicitOwner = mapOf(MEDIA_PATH to mapOf(MEDIA_TRANSPORT to emptyMap<String, Any?>()))
        assertTrue(implicitOwner.hasMediaTransport(DEVICE_PATH))
        assertFalse(implicitOwner.hasMediaTransport(DEVICE_PATH.dropLast(1)))
        assertFalse((baseObjects() + implicitOwner).hasMedia("00:11:22:33:44:55"))
    }

    @Test
    fun releaseTargetsTheAdapterHoldingAudioWhenBothHavePairedTheSameAddress() = runBlocking {
        val usb = "/org/bluez/hci1"
        val headset = "$usb/${DEVICE_PATH.substringAfterLast('/')}"
        val mediaPath = "$headset/fd0"
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + mapOf(
                DEVICE_PATH to deviceLinks(false),
                usb to mapOf(ADAPTER to mapOf("Powered" to true)),
                headset to deviceLinks(false),
                mediaPath to mapOf(MEDIA_TRANSPORT to mapOf("Device" to headset, "UUID" to A2DP_UUID)),
            )
            onAudioDisconnect = { path ->
                assertEquals(headset, path)
                snapshot -= mediaPath
            }
        }
        val transport = BluezControlTransport(this, bus)
        try {
            assertEquals(1, transport.bondedDevices().size)
            transport.audioProfiles.release("00:11:22:33:44:55")
        } finally {
            transport.close()
        }
    }

    @Test
    fun audioHandoffWaitsForMediaTransportAndReleasesBothBearers() = runBlocking {
        val bus = FakeBluezBus().apply { snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false)) }
        val transport = BluezControlTransport(this, bus)
        val audio = transport.audioProfiles
        val address = "00:11:22:33:44:55"
        try {
            assertFalse(audio.isConnected(address))
            bus.onAudioConnect = { snapshotPath ->
                assertEquals(DEVICE_PATH, snapshotPath)
                bus.snapshot += MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID))
            }
            audio.acquire(address)
            assertTrue(audio.isConnected(address))
            bus.onAudioDisconnect = { bus.snapshot = baseObjects().filterKeys { it != DEVICE_PATH } }
            audio.release(address)
            assertFalse(audio.isConnected(address))
        } finally {
            transport.close()
        }
    }

    @Test
    fun acquiringAnIdleI5WaitsForItsLeBearerBeforeRequestingA2dp() = runTest {
        val calls = mutableListOf<String>()
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false, classic = false, le = false))
            onConnect = { calls += "LE" }
            onAudioConnect = { path ->
                assertEquals(DEVICE_PATH, path)
                calls += "A2DP"
                snapshot += MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to path, "UUID" to A2DP_UUID))
            }
        }
        val acquire = async(start = CoroutineStart.UNDISPATCHED) { BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55") }
        assertEquals(listOf("LE"), calls)
        assertFalse(acquire.isCompleted)
        bus.snapshot += DEVICE_PATH to deviceLinks(true, classic = false, le = true)
        acquire.await()
        assertEquals(listOf(true), bus.connectBearers)
        assertEquals(listOf("LE", "A2DP"), calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun a2dpRetriesWhileBluezIsBrowsingLeThenOnlyObservesTheAcceptedRequest() = runTest {
        val attempts = mutableListOf<Long>()
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false, classic = false, le = false))
            onConnect = {
                snapshot += DEVICE_PATH to deviceLinks(true, classic = false, le = true)
                backgroundScope.launch {
                    delay(1_587)
                    val interfaces = snapshot.getValue(DEVICE_PATH)
                    snapshot += DEVICE_PATH to (interfaces + (DEVICE to interfaces.getValue(DEVICE) + ("ServicesResolved" to true)))
                }
            }
            onAudioConnect = { path ->
                attempts += testScheduler.currentTime
                if (snapshot[path]?.get(DEVICE)?.get("ServicesResolved") != true) throw org.bluez.Error.InProgress("browsing")
                backgroundScope.launch {
                    delay(450)
                    snapshot += MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to path, "UUID" to A2DP_UUID))
                }
            }
        }
        BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55")
        assertEquals(0L, attempts.first())
        assertEquals(1, attempts.count { it >= 1_587 })
    }

    @Test
    fun a2dpStopsRequestingAsSoonAsMediaIsConnected() = runTest {
        val bus = FakeBluezBus().apply { snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(true, classic = false, le = true)) }
        var requests = 0
        bus.onAudioConnect = { path ->
            requests++
            bus.snapshot += MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to path, "UUID" to A2DP_UUID))
            throw org.bluez.Error.InProgress("another profile connection")
        }
        val audio = BluezAudioProfiles { bus }
        audio.acquire("00:11:22:33:44:55")
        audio.acquire("00:11:22:33:44:55")
        assertEquals(1, requests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aContinuouslyBusyProfileCannotExtendTheAcquisitionDeadline() = runTest {
        val bus = FakeBluezBus().apply { snapshot = baseObjects() }
        var requests = 0
        bus.onAudioConnect = {
            requests++
            throw org.bluez.Error.InProgress("browsing")
        }
        val failure = assertFailsWith<IllegalStateException> { BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55") }
        assertEquals("Linux 接入耳机音频超时", failure.message)
        assertTrue(requests > 1)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun alreadyConnectedWaitsForMediaAndOtherAudioErrorsAreNeverRetried() = runTest {
        val bus = FakeBluezBus().apply { snapshot = baseObjects() }
        val audio = BluezAudioProfiles { bus }
        var requests = 0
        bus.onAudioConnect = { path ->
            requests++
            backgroundScope.launch {
                delay(450)
                bus.snapshot += MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to path, "UUID" to A2DP_UUID))
            }
            throw org.bluez.Error.AlreadyConnected("already connected")
        }
        audio.acquire("00:11:22:33:44:55")
        assertEquals(1, requests)

        bus.snapshot -= MEDIA_PATH
        bus.onAudioConnect = {
            requests++
            throw org.bluez.Error.NotReady("not ready")
        }
        assertFailsWith<org.bluez.Error.NotReady> { audio.acquire("00:11:22:33:44:55") }
        assertEquals(2, requests)
    }

    @Test
    fun aLeRequestThatNeverConnectsIsWithdrawnWithoutRequestingAudio() = runTest {
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false, classic = false, le = false))
            onAudioConnect = { fail("A2DP must wait for the LE connection") }
        }
        val disconnected = mutableListOf<String>()
        bus.onLeDisconnect = { disconnected += it }
        val failure = assertFailsWith<IllegalStateException> {
            BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55")
        }
        assertContains(failure.message.orEmpty(), "LE")
        assertEquals(listOf(DEVICE_PATH), disconnected)
    }

    @Test
    fun failedAudioAcquisitionReleasesOnlyTheLeConnectionItStarted() = runTest {
        for (existing in listOf("none", "LE", "classic")) {
            val bus = FakeBluezBus().apply {
                snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(existing != "none", classic = existing == "classic", le = existing == "LE"))
                onConnect = { snapshot += DEVICE_PATH to deviceLinks(true, classic = false, le = true) }
                onAudioConnect = { throw IllegalStateException("A2DP rejected") }
            }
            val disconnected = mutableListOf<String>()
            bus.onLeDisconnect = { disconnected += it }
            val failure = assertFailsWith<IllegalStateException> {
                BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55")
            }
            assertEquals("A2DP rejected", failure.message)
            assertEquals(if (existing == "none") 1 else 0, bus.connects, existing)
            assertEquals(if (existing == "none") listOf(DEVICE_PATH) else emptyList(), disconnected, existing)
        }
    }

    @Test
    fun acquiringWhileAnotherLeRequestIsInProgressBorrowsItsConnection() = runTest {
        for (response in listOf(org.bluez.Error.InProgress("pending"), org.bluez.Error.AlreadyConnected("existing"))) {
            val bus = FakeBluezBus().apply {
                snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false, classic = false, le = false))
                connectFailure = response
                onAudioConnect = { throw IllegalStateException("audio failed") }
            }
            val leDisconnects = mutableListOf<String>()
            bus.onLeDisconnect = { leDisconnects += it }
            val acquire = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<IllegalStateException> { BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55") }
            }
            assertFalse(acquire.isCompleted)
            bus.snapshot += DEVICE_PATH to deviceLinks(true, classic = false, le = true)
            assertEquals("audio failed", acquire.await().message)
            assertTrue(leDisconnects.isEmpty())
        }
    }

    @Test
    fun aRejectedLeRequestDoesNotCleanUpAConnectionItNeverOwned() = runTest {
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false, classic = false, le = false))
            connectFailure = org.bluez.Error.NotPermitted("not permitted")
        }
        val disconnects = mutableListOf<String>()
        bus.onLeDisconnect = { disconnects += it }
        assertFailsWith<org.bluez.Error.NotPermitted> { BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55") }
        assertTrue(disconnects.isEmpty())
    }

    @Test
    fun cancellingAcquisitionKeepsClassicThatConnectedWhileWaitingForMedia() = runTest {
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(false, classic = false, le = false))
            onConnect = { snapshot += DEVICE_PATH to deviceLinks(true, classic = false, le = true) }
            onAudioConnect = { snapshot += DEVICE_PATH to deviceLinks(true, classic = true, le = true) }
            onLeDisconnect = { snapshot += DEVICE_PATH to deviceLinks(true, classic = true, le = false) }
            onAudioDisconnect = { snapshot += DEVICE_PATH to deviceLinks(false, classic = false, le = false) }
        }
        val acquire = launch(start = CoroutineStart.UNDISPATCHED) { BluezAudioProfiles { bus }.acquire("00:11:22:33:44:55") }
        assertTrue(acquire.isActive)
        acquire.cancelAndJoin()
        val remaining = bus.snapshot.getValue(DEVICE_PATH)
        assertEquals(true, remaining[CLASSIC_BEARER]?.get("Connected"))
        assertEquals(false, remaining[LE_BEARER]?.get("Connected"))
    }

    @Test
    fun leOnlyHeadphonesAreHeldUntilTheLeLinkActuallyDrops() = runTest {
        val address = "00:11:22:33:44:55"
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(true, classic = false, le = true))
        }
        val audio = BluezAudioProfiles { bus }
        assertTrue(audio.isConnected(address))
        assertFalse(audio.isMediaConnected(address))
        val released = async(start = CoroutineStart.UNDISPATCHED) { audio.release(address) }
        assertFalse(released.isCompleted)
        bus.snapshot += DEVICE_PATH to deviceLinks(false, classic = false, le = false)
        released.await()
    }

    @Test
    fun releaseStillWaitsForLeAfterClassicAndMediaAreGone() = runTest {
        val address = "00:11:22:33:44:55"
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + mapOf(
                DEVICE_PATH to deviceLinks(true, classic = true, le = true),
                MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID)),
            )
            onAudioDisconnect = {
                snapshot -= MEDIA_PATH
                snapshot += DEVICE_PATH to deviceLinks(true, classic = false, le = true)
            }
        }
        val failure = assertFailsWith<IllegalStateException> { BluezAudioProfiles { bus }.release(address) }
        assertContains(failure.message.orEmpty(), "尚未让出")
    }

    @Test
    fun releaseUsesTheAggregateConnectionOnBluezWithoutBearerInterfaces() = runTest {
        val address = "00:11:22:33:44:55"
        val bus = FakeBluezBus().apply { snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(true)) }
        val audio = BluezAudioProfiles { bus }
        assertTrue(audio.isConnected(address))
        val released = async(start = CoroutineStart.UNDISPATCHED) { audio.release(address) }
        assertFalse(released.isCompleted)
        bus.snapshot += DEVICE_PATH to deviceLinks(false)
        released.await()
        assertFalse(audio.isConnected(address))
    }

    @Test
    fun missingBearerFactsNeedAnAggregateReadingToProveDisconnection() = runTest {
        val address = "00:11:22:33:44:55"
        val bus = FakeBluezBus().apply { snapshot = baseObjects() + (DEVICE_PATH to deviceLinks(true, classic = false)) }
        val audio = BluezAudioProfiles { bus }
        assertTrue(audio.isConnected(address))
        val partial = deviceLinks(false, classic = false)
        bus.snapshot += DEVICE_PATH to (partial + (DEVICE to partial.getValue(DEVICE) - "Connected"))
        assertFailsWith<IllegalStateException> { audio.isConnected(address) }
        assertFailsWith<IllegalStateException> { audio.release(address) }
    }

    @Test
    fun releaseDropsActiveLinksOnBothAdaptersOnlyForTheSelectedHeadphones() = runTest {
        val usb = "/org/bluez/hci1"
        val second = "$usb/${DEVICE_PATH.substringAfterLast('/')}"
        val unrelated = "$usb/dev_00_11_22_33_44_77"
        val calls = mutableSetOf<String>()
        val bus = FakeBluezBus().apply {
            snapshot = baseObjects() + mapOf(
                DEVICE_PATH to deviceLinks(true, classic = true, le = false),
                usb to mapOf(ADAPTER to mapOf("Powered" to true)),
                second to deviceLinks(true, classic = false, le = true),
                unrelated to mapOf(DEVICE to mapOf("Address" to "00:11:22:33:44:77", "Paired" to true, "Connected" to true)),
            )
            onAudioDisconnect = { path ->
                calls += path
                snapshot += path to deviceLinks(false, classic = false, le = false)
            }
        }
        BluezAudioProfiles { bus }.release("00:11:22:33:44:55")
        assertEquals(setOf(DEVICE_PATH, second), calls)
    }

    @Test
    fun poweredOffAdapterIsUnavailableAndPairedQueryIsSafe() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = mapOf(ADAPTER_PATH to mapOf(ADAPTER to mapOf("Powered" to false))) }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        assertIs<TransportAvailability.Unavailable>(transport.availability.value)
        assertEquals(emptyList(), transport.bondedDevices())
        transport.close()
    }
}

private const val ADAPTER_PATH = "/org/bluez/hci0"
private const val DEVICE_PATH = "$ADAPTER_PATH/dev_00_11_22_33_44_55"
private const val MEDIA_PATH = "$DEVICE_PATH/fd0"
private const val A2DP_UUID = "0000110b-0000-1000-8000-00805f9b34fb"
private fun baseObjects(): BluezObjects = mapOf(
    ADAPTER_PATH to mapOf(ADAPTER to mapOf("Powered" to true)),
    DEVICE_PATH to mapOf(
        DEVICE to mapOf(
            "Address" to "00:11:22:33:44:55",
            "Name" to "ROSE EarFree i5",
            "Paired" to true,
            "Connected" to true,
            "UUIDs" to listOf(A2DP_UUID),
            "ManufacturerData" to mapOf(UInt16(0x1234) to byteArrayOf(1, 2, 3)),
        ),
    ),
)

private fun deviceLinks(aggregate: Boolean, classic: Boolean? = null, le: Boolean? = null): Map<String, Map<String, Any?>> = buildMap {
    put(DEVICE, baseObjects().getValue(DEVICE_PATH).getValue(DEVICE) + ("Connected" to aggregate))
    classic?.let { put(CLASSIC_BEARER, mapOf("Connected" to it)) }
    le?.let { put(LE_BEARER, mapOf("Connected" to it)) }
}

private val i5Replies = mapOf(
    "0101" to "09ff00000101011100000164620000ffe2", "0602" to "09ff00000106020e000000010020",
    "0201" to "09ff00000102010b000118", "0603" to "09ff00000106030b00011e",
    "0801" to "09ff00000108010b00011e", "0d01" to "09ff0000010d010e00010205002d",
    "0401" to "09ff00000104011200060502040605030746", "0a01" to "09ff0000010a010b00001f",
    "0605" to "09ff00000106050e000101010126", "0c01" to "09ff0000010c010b000122",
    "0607" to "09ff00000106070b000223", "0604" to "09ff00000106040b00001e",
    "0e03" to "09ff0000010e030b000126",
)
