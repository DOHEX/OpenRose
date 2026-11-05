package io.github.dohex.openrose.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

class AudioFactsTest {
    @Test
    fun bondedDevicesMatchModelsAndNormalizeAddresses() {
        val devices = pairedAudioDevices(listOf(
            RawAudioDevice("EarFree i5", " aa:bb:cc:dd:ee:ff "),
            RawAudioDevice("Other", "11:22:33:44:55:66"),
            RawAudioDevice("EarFree i5", "invalid"),
        ))
        assertEquals(listOf(DeviceId("AA:BB:CC:DD:EE:FF")), devices.map { it.deviceId })
        assertEquals("AA:BB:CC:DD:EE:FF", devices.single().classicAddress)
    }

    @Test
    fun profileEvidenceRequiresConsistentCompleteSnapshots() {
        val classic = snapshot(a2dp = setOf(address))
        assertEquals(AudioProfileEvidence.CLASSIC, inferAudioProfile(classic, setOf(address)))
        assertEquals(AudioProfileEvidence.LE_AUDIO, inferAudioProfile(snapshot(a2dp = setOf(address), le = setOf(address)), setOf(address)))
        assertEquals(AudioProfileEvidence.UNKNOWN, inferAudioProfile(snapshot(), setOf(address)))
        assertEquals(AudioProfileEvidence.UNKNOWN, inferAudioProfile(snapshot(a2dp = setOf(address), le = setOf(other)), setOf(address)))
        assertEquals(AudioProfileEvidence.UNKNOWN, inferAudioProfile(classic.copy(leAudioObservable = false), setOf(address)))
        assertEquals(AudioProfileEvidence.UNKNOWN, inferAudioProfile(classic.copy(profiles = classic.profiles - AudioProfile.HEADSET), setOf(address)))
        val inconsistent = classic.profiles.getValue(AudioProfile.A2DP)!!.copy(states = mapOf(address to AudioConnectionState.DISCONNECTED))
        assertEquals(AudioProfileEvidence.UNKNOWN, inferAudioProfile(classic.copy(profiles = classic.profiles + (AudioProfile.A2DP to inconsistent)), setOf(address)))
        assertEquals(AudioProfileEvidence.CLASSIC, inferAudioProfile(classic, setOf(" aa:bb:cc:dd:ee:ff ")))
    }

    @Test
    fun unavailableLeProfileKeepsClassicAudioTargetButLeavesEvidenceUnknown() = runTest {
        val driver = FakeAudioDriver()
        val facts = AudioFacts(driver)
        val classic = snapshot(a2dp = setOf(address))
        val incomplete = classic.copy(profiles = classic.profiles + (AudioProfile.LE_AUDIO to null))
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { facts.audioConnectedDevice.collect { values += it } }
        runCurrent()
        driver.queries.receive().complete(incomplete)
        runCurrent()
        assertEquals(listOf<DeviceId?>(DeviceId(address)), values)

        var evidence: AudioProfileEvidence? = null
        val request = backgroundScope.launch { evidence = facts.connectionFacts(DeviceId(address), address).audioProfile }
        runCurrent()
        driver.queries.receive().complete(incomplete)
        request.join()
        assertEquals(AudioProfileEvidence.UNKNOWN, evidence)
    }

    @Test
    fun eventsDuringQueryWinWithoutPrematureInitialEmission() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        val query = driver.queries.receive()
        driver.emit(AudioProfile.A2DP, true)
        runCurrent()
        assertTrue(values.isEmpty())
        query.complete(snapshot())
        runCurrent()
        assertEquals(listOf<DeviceId?>(DeviceId(address)), values)
        driver.emit(AudioProfile.A2DP, false)
        runCurrent()
        assertEquals(listOf(DeviceId(address), null), values)
    }

    @Test
    fun disconnectDuringQueryDoesNotEraseOtherProfileFact() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        val query = driver.queries.receive()
        driver.emit(AudioProfile.A2DP, false)
        runCurrent()
        query.complete(snapshot(a2dp = setOf(address), headset = setOf(address)))
        runCurrent()
        assertEquals(listOf<DeviceId?>(DeviceId(address)), values)
        driver.emit(AudioProfile.HEADSET, false)
        runCurrent()
        assertEquals(listOf(DeviceId(address), null), values)
    }

    @Test
    fun disconnectDuringQueryRemovesStaleSnapshotConnection() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        val query = driver.queries.receive()
        driver.emit(AudioProfile.A2DP, false)
        runCurrent()
        query.complete(snapshot(a2dp = setOf(address)))
        runCurrent()
        assertEquals(listOf<DeviceId?>(null), values)
    }

    @Test
    fun eventsOnlySelectBondedSupportedDevicesWithValidAddresses() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        driver.queries.receive().complete(snapshot())
        runCurrent()
        driver.eventsFlow.emit(RawAudioEvent.ProfileChanged(AudioProfile.A2DP, RawAudioDevice("Other", other), true, true))
        driver.eventsFlow.emit(RawAudioEvent.ProfileChanged(AudioProfile.A2DP, RawAudioDevice("EarFree i5", "invalid"), true, true))
        driver.eventsFlow.emit(RawAudioEvent.ProfileChanged(AudioProfile.A2DP, RawAudioDevice("EarFree i5", address), false, true))
        runCurrent()
        assertEquals(listOf<DeviceId?>(null), values)
        driver.emit(AudioProfile.A2DP, true)
        runCurrent()
        assertEquals(listOf(null, DeviceId(address)), values)
    }

    @Test
    fun restartDiscardsOldQueryAndRequeries() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        val old = driver.queries.receive()
        driver.eventsFlow.emit(RawAudioEvent.AdapterOff)
        driver.eventsFlow.emit(RawAudioEvent.AdapterOn)
        runCurrent()
        val fresh = driver.queries.receive()
        old.complete(snapshot(a2dp = setOf(address)))
        runCurrent()
        assertEquals(listOf<DeviceId?>(null), values)
        fresh.complete(snapshot(headset = setOf(address)))
        runCurrent()
        assertEquals(listOf(null, DeviceId(address)), values)
    }
    @Test
    fun unreadableInitialProbeDoesNotSuppressLaterReadableProfileEvents() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        driver.queries.receive().complete(snapshot().copy(enabled = false, bondedDevices = emptyList(), profiles = emptyMap()))
        runCurrent()
        assertEquals(listOf<DeviceId?>(null), values)
        driver.emit(AudioProfile.A2DP, true)
        runCurrent()
        assertEquals(listOf(null, DeviceId(address)), values)
    }

    @Test
    fun readableEventDuringUnreadableProbeWinsOverThatProbe() = runTest {
        val driver = FakeAudioDriver()
        val values = mutableListOf<DeviceId?>()
        backgroundScope.launch { AudioFacts(driver).audioConnectedDevice.collect { values += it } }
        runCurrent()
        val query = driver.queries.receive()
        driver.emit(AudioProfile.A2DP, true)
        runCurrent()
        query.complete(snapshot().copy(enabled = false, bondedDevices = emptyList(), profiles = emptyMap()))
        runCurrent()
        assertEquals(listOf<DeviceId?>(DeviceId(address)), values)
    }


    private class FakeAudioDriver : AudioFactsDriver {
        val eventsFlow = MutableSharedFlow<RawAudioEvent>(extraBufferCapacity = 16)
        override val events: Flow<RawAudioEvent> = eventsFlow.onStart { emit(RawAudioEvent.Ready) }
        val queries = Channel<CompletableDeferred<RawAudioSnapshot>>(Channel.UNLIMITED)
        override suspend fun bondedDevices(): List<RawAudioDevice> = emptyList()
        override suspend fun snapshot(targetAddresses: Set<String>): RawAudioSnapshot {
            val result = CompletableDeferred<RawAudioSnapshot>()
            queries.send(result)
            return result.await()
        }
        suspend fun emit(profile: AudioProfile, connected: Boolean) {
            eventsFlow.emit(RawAudioEvent.ProfileChanged(profile, RawAudioDevice("EarFree i5", address.lowercase()), true, connected))
        }
    }

    companion object {
        private const val address = "AA:BB:CC:DD:EE:FF"
        private const val other = "11:22:33:44:55:66"
        private fun snapshot(a2dp: Set<String> = emptySet(), headset: Set<String> = emptySet(), le: Set<String> = emptySet()): RawAudioSnapshot {
            fun profile(connected: Set<String>): RawProfileSnapshot = RawProfileSnapshot(
                connected.toList(),
                (connected + address).associateWith { if (it in connected) AudioConnectionState.CONNECTED else AudioConnectionState.DISCONNECTED },
            )
            return RawAudioSnapshot(true, true, true, listOf(RawAudioDevice("EarFree i5", address)), mapOf(
                AudioProfile.A2DP to profile(a2dp), AudioProfile.HEADSET to profile(headset), AudioProfile.LE_AUDIO to profile(le),
            ))
        }
    }
}
