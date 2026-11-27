package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.openrose_control.AudioConnectionState
import uniffi.openrose_control.AudioProfile
import uniffi.openrose_control.RawAudioDevice
import uniffi.openrose_control.RawAudioEvent
import uniffi.openrose_control.RawAudioSnapshot
import uniffi.openrose_control.RawProfileSnapshot
import uniffi.openrose_ffi.AudioEvents
import uniffi.openrose_ffi.AudioFactsDriver
import io.github.dohex.openrose.core.transport.AudioConnectionState as KotlinAudioConnectionState
import io.github.dohex.openrose.core.transport.AudioFactsDriver as KotlinAudioFactsDriver
import io.github.dohex.openrose.core.transport.AudioProfile as KotlinAudioProfile
import io.github.dohex.openrose.core.transport.RawAudioDevice as KotlinRawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent as KotlinRawAudioEvent
import io.github.dohex.openrose.core.transport.RawAudioSnapshot as KotlinRawAudioSnapshot
import io.github.dohex.openrose.core.transport.RawProfileSnapshot as KotlinRawProfileSnapshot

private const val REGISTRATION_TIMEOUT_MILLIS = 5_000L

internal class KotlinCoreAudioFacts(private val source: KotlinAudioFactsDriver, private val scope: CoroutineScope) : AudioFactsDriver {
    private var subscription: Job? = null

    @Synchronized
    override fun subscribe(): AudioEvents {
        subscription?.cancel()
        val registered = CompletableDeferred<Unit>()
        val events = Channel<RawAudioEvent>(Channel.UNLIMITED)
        subscription = scope.launch { forward(registered, events) }
        runBlocking { withTimeoutOrNull(REGISTRATION_TIMEOUT_MILLIS) { registered.await() } }
        return object : AudioEvents {
            override suspend fun nextEvent(): RawAudioEvent? = events.receiveCatching().getOrNull()
        }
    }

    override suspend fun bondedDevices(): List<RawAudioDevice> = source.bondedDevices().map { it.toRust() }

    override suspend fun snapshot(targetAddresses: List<String>): RawAudioSnapshot? = source.snapshot(targetAddresses.toSet()).toRust()

    private suspend fun forward(registered: CompletableDeferred<Unit>, events: Channel<RawAudioEvent>) {
        try {
            source.events.pumpInto(events) { event ->
                if (event == KotlinRawAudioEvent.Ready) registered.complete(Unit) else event.toRust()?.let { events.send(it) }
            }
        } finally {
            registered.complete(Unit)
        }
    }
}

private fun KotlinRawAudioEvent.toRust(): RawAudioEvent? = when (this) {
    KotlinRawAudioEvent.AdapterOff -> RawAudioEvent.AdapterOff
    KotlinRawAudioEvent.AdapterOn -> RawAudioEvent.AdapterOn
    KotlinRawAudioEvent.Ready -> null
    KotlinRawAudioEvent.BondedDevicesChanged -> RawAudioEvent.BondedDevicesChanged
    is KotlinRawAudioEvent.ProfileChanged -> RawAudioEvent.ProfileChanged(profile.toRust(), device.toRust(), bonded, connected)
}

private fun KotlinRawAudioDevice.toRust(): RawAudioDevice = RawAudioDevice(name, address, alias)

private val audioProfiles = EnumNames(AudioProfile.entries)

private val audioConnectionStates = EnumNames(AudioConnectionState.entries)

private fun KotlinAudioProfile.toRust(): AudioProfile = audioProfiles.of(this)

private fun KotlinAudioConnectionState.toRust(): AudioConnectionState = audioConnectionStates.of(this)

private fun KotlinRawProfileSnapshot.toRust(): RawProfileSnapshot = RawProfileSnapshot(
    connectedAddresses,
    states.mapValues { (_, state) -> state.toRust() },
)

private fun KotlinRawAudioSnapshot.toRust(): RawAudioSnapshot = RawAudioSnapshot(
    enabled,
    sppAvailable,
    leAudioObservable,
    bondedDevices.map { it.toRust() },
    profiles.entries.associate { (profile, snapshot) -> profile.toRust() to snapshot?.toRust() },
)
