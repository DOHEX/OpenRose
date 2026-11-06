package io.github.dohex.openrose.core.transport

import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Raw platform facts. A null profile snapshot means it could not be read reliably. */
data class RawAudioDevice(val name: String?, val address: String)
enum class AudioProfile { A2DP, HEADSET, LE_AUDIO }
enum class AudioConnectionState { CONNECTED, DISCONNECTED }
data class RawProfileSnapshot(
    val connectedAddresses: List<String>,
    val states: Map<String, AudioConnectionState>,
)
data class RawAudioSnapshot(
    val enabled: Boolean,
    val sppAvailable: Boolean,
    val leAudioObservable: Boolean,
    val bondedDevices: List<RawAudioDevice>,
    val profiles: Map<AudioProfile, RawProfileSnapshot?>,
)
sealed interface RawAudioEvent {
    data object AdapterOff : RawAudioEvent
    data object Ready : RawAudioEvent
    data object AdapterOn : RawAudioEvent
    data class ProfileChanged(
        val profile: AudioProfile,
        val device: RawAudioDevice,
        val bonded: Boolean,
        val connected: Boolean,
    ) : RawAudioEvent
}
interface AudioFactsDriver {
    /** Emit Ready after registering, before completing any subsequent snapshot. */
    val events: Flow<RawAudioEvent>
    suspend fun bondedDevices(): List<RawAudioDevice>
    suspend fun snapshot(targetAddresses: Set<String>): RawAudioSnapshot
}

fun normalizeBluetoothAddress(value: String?): String? {
    val address = value?.trim()?.uppercase() ?: return null
    if (address.length != 17) return null
    return address.takeIf { text ->
        text.indices.all { index ->
            if (index % 3 == 2) text[index] == ':' else text[index] in '0'..'9' || text[index] in 'A'..'F'
        }
    }
}

private fun supportedAudioModel(device: RawAudioDevice): ModelProfile? = ModelProfile.match(device.name)

fun pairedAudioDevices(devices: List<RawAudioDevice>): List<DeviceCandidate> = devices.mapNotNull { device ->
    val profile = supportedAudioModel(device) ?: return@mapNotNull null
    val address = normalizeBluetoothAddress(device.address) ?: return@mapNotNull null
    DeviceCandidate(DeviceId(address), device.name, null, profile, "", address)
}

fun inferAudioProfile(snapshot: RawAudioSnapshot, targetAddresses: Set<String>): AudioProfileEvidence {
    if (!snapshot.enabled || !snapshot.leAudioObservable) return AudioProfileEvidence.UNKNOWN
    val targets = targetAddresses.mapNotNull(::normalizeBluetoothAddress).toSet()
    if (targets.isEmpty()) return AudioProfileEvidence.UNKNOWN
    val profiles = AudioProfile.entries.associateWith { profile ->
        val raw = snapshot.profiles[profile] ?: return AudioProfileEvidence.UNKNOWN
        val connected = raw.connectedAddresses.map { normalizeBluetoothAddress(it) ?: return AudioProfileEvidence.UNKNOWN }.toSet()
        val states = raw.states.map { (address, state) ->
            (normalizeBluetoothAddress(address) ?: return AudioProfileEvidence.UNKNOWN) to state
        }.toMap()
        if ((targets + connected).any { it !in states } || connected.any { states[it] != AudioConnectionState.CONNECTED } ||
            states.any { (address, state) -> state == AudioConnectionState.CONNECTED && address !in connected }
        ) {
            return AudioProfileEvidence.UNKNOWN
        }
        connected
    }
    val le = profiles.getValue(AudioProfile.LE_AUDIO)
    if (le.any { it in targets }) return AudioProfileEvidence.LE_AUDIO
    if (le.isNotEmpty()) return AudioProfileEvidence.UNKNOWN
    return if (profiles.getValue(AudioProfile.A2DP).any { it in targets } ||
        profiles.getValue(AudioProfile.HEADSET).any { it in targets }
    ) {
        AudioProfileEvidence.CLASSIC
    } else {
        AudioProfileEvidence.UNKNOWN
    }
}

/** Shared paired-device and audio policy over platform snapshots and broadcasts. */
class AudioFacts(private val driver: AudioFactsDriver) {
    suspend fun bondedDevices(): List<DeviceCandidate> = pairedAudioDevices(driver.bondedDevices())

    suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts {
        val targets = listOfNotNull(normalizeBluetoothAddress(deviceId.value), normalizeBluetoothAddress(classicAddress)).toSet()
        val snapshot = driver.snapshot(targets)
        return TransportFacts(
            snapshot.enabled && snapshot.sppAvailable && normalizeBluetoothAddress(classicAddress) != null,
            inferAudioProfile(snapshot, targets),
        )
    }

    val audioConnectedDevice: Flow<DeviceId?> = channelFlow {
        val inbox = Channel<AudioMessage>(Channel.BUFFERED)
        val connected = mutableMapOf<AudioProfile, MutableSet<String>>()
        val changes = mutableMapOf<Pair<AudioProfile, String>, Boolean>()
        val eventDevices = mutableMapOf<String, RawAudioDevice?>()
        val bonded = mutableSetOf<String>()
        var epoch = 0
        var querying = true
        var enabled = true
        var ready = false
        var emitted = false
        var current: DeviceId? = null

        fun publish() {
            val next = connected.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it in bonded }?.let(::DeviceId)
            if (!emitted || next != current) {
                emitted = true
                current = next
                trySend(next)
            }
        }
        fun query(resetChanges: Boolean) {
            val queryEpoch = epoch
            querying = true
            if (resetChanges) {
                changes.clear()
                eventDevices.clear()
            }
            launch { inbox.send(AudioMessage.Result(queryEpoch, driver.snapshot(emptySet()))) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) { driver.events.collect { inbox.send(AudioMessage.Event(it)) } }
        for (message in inbox) {
            when (message) {
                is AudioMessage.Result -> {
                    if (message.epoch != epoch || !enabled) continue
                    val snapshot = message.snapshot
                    connected.clear()
                    bonded.clear()
                    if (snapshot.enabled) {
                        for (profile in listOf(AudioProfile.A2DP, AudioProfile.HEADSET)) {
                            val addresses = snapshot.profiles[profile]?.connectedAddresses.orEmpty().mapNotNull(::normalizeBluetoothAddress)
                            connected.getOrPut(profile) { mutableSetOf() }.addAll(addresses)
                        }
                        for (device in snapshot.bondedDevices) {
                            if (supportedAudioModel(device) != null) normalizeBluetoothAddress(device.address)?.let(bonded::add)
                        }
                    }
                    // An unreadable probe is not an adapter-off event. Readable broadcasts still win.
                    for ((address, device) in eventDevices) {
                        if (device != null && supportedAudioModel(device) != null) bonded.add(address) else bonded.remove(address)
                    }
                    for ((key, value) in changes) {
                        val set = connected.getOrPut(key.first) { mutableSetOf() }
                        if (value) set.add(key.second) else set.remove(key.second)
                    }
                    querying = false
                    publish()
                }

                is AudioMessage.Event -> when (val event = message.event) {
                    RawAudioEvent.Ready -> {
                        if (!ready) {
                            ready = true
                            if (enabled) query(resetChanges = false)
                        }
                    }

                    RawAudioEvent.AdapterOff -> {
                        epoch++
                        enabled = false
                        querying = false
                        connected.clear()
                        bonded.clear()
                        changes.clear()
                        eventDevices.clear()
                        publish()
                    }

                    RawAudioEvent.AdapterOn -> {
                        epoch++
                        enabled = true
                        connected.clear()
                        bonded.clear()
                        if (ready) query(resetChanges = true)
                    }

                    is RawAudioEvent.ProfileChanged -> {
                        if (!enabled || event.profile == AudioProfile.LE_AUDIO) continue
                        val address = normalizeBluetoothAddress(event.device.address) ?: continue
                        val device = event.device.takeIf { event.bonded }
                        val hasIdentity = !event.bonded || event.device.name != null
                        if (querying) {
                            changes[event.profile to address] = event.connected
                            if (hasIdentity) eventDevices[address] = device
                        }
                        if (hasIdentity) {
                            if (device != null && supportedAudioModel(device) != null) bonded.add(address) else bonded.remove(address)
                        }
                        val set = connected.getOrPut(event.profile) { mutableSetOf() }
                        if (event.connected) set.add(address) else set.remove(address)
                        if (!querying) publish()
                    }
                }
            }
        }
    }.distinctUntilChanged()
}

private sealed interface AudioMessage {
    data class Event(val event: RawAudioEvent) : AudioMessage
    data class Result(val epoch: Int, val snapshot: RawAudioSnapshot) : AudioMessage
}
