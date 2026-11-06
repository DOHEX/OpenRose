package io.github.dohex.openrose

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.core.transport.AudioConnectionState
import io.github.dohex.openrose.core.transport.AudioFactsDriver
import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.RawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent
import io.github.dohex.openrose.core.transport.RawAudioSnapshot
import io.github.dohex.openrose.core.transport.RawProfileSnapshot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Exposes public Android Bluetooth facts; interpretation belongs to the shared core. */
internal class AndroidAudioProfiles(private val context: Context) : AudioFactsDriver {
    override val events: Flow<RawAudioEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) return
                        val device = getDeviceExtra(intent) ?: return
                        val event = try {
                            RawAudioEvent.ProfileChanged(
                                if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                                    AudioProfile.A2DP
                                } else {
                                    AudioProfile.HEADSET
                                },
                                RawAudioDevice(device.name, device.address),
                                device.bondState == BluetoothDevice.BOND_BONDED,
                                state == BluetoothProfile.STATE_CONNECTED,
                            )
                        } catch (_: SecurityException) {
                            return
                        }
                        trySend(event)
                    }

                    BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    ) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> trySend(RawAudioEvent.AdapterOff)
                        BluetoothAdapter.STATE_ON -> trySend(RawAudioEvent.AdapterOn)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        trySend(RawAudioEvent.Ready)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun getDeviceExtra(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    @SuppressLint("MissingPermission")
    override suspend fun bondedDevices(): List<RawAudioDevice> = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !hasBluetoothPermission() || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            emptyList()
        } else {
            readBondedDevices(adapter)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBondedDevices(adapter: BluetoothAdapter): List<RawAudioDevice> = try {
        adapter.bondedDevices?.map { RawAudioDevice(it.name, it.address) }.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    override suspend fun snapshot(targetAddresses: Set<String>): RawAudioSnapshot = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val enabled = adapter != null && hasBluetoothPermission() && runCatching { adapter.isEnabled }.getOrDefault(false)
        val leObservable = Build.VERSION.SDK_INT !in Build.VERSION_CODES.S until Build.VERSION_CODES.TIRAMISU
        val spp = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        if (!enabled) return@withContext RawAudioSnapshot(false, spp, leObservable, emptyList(), emptyMap())
        val bonded = readBondedDevices(adapter)
        val leases = mutableMapOf<AudioProfile, ProfileProxyLease?>()
        try {
            withTimeoutOrNull(PROFILE_PROBE_TIMEOUT_MILLIS) {
                val ids = buildMap {
                    put(AudioProfile.A2DP, BluetoothProfile.A2DP)
                    put(AudioProfile.HEADSET, BluetoothProfile.HEADSET)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) put(AudioProfile.LE_AUDIO, BluetoothProfile.LE_AUDIO)
                }
                for ((profile, id) in ids) leases[profile] = requestProfileProxy(adapter, id)
            }
            // A stalled proxy must not discard facts from profiles that already arrived.
            val profiles = leases.mapValues { (_, lease) -> lease?.snapshot(targetAddresses) }.toMutableMap()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                profiles[AudioProfile.LE_AUDIO] = RawProfileSnapshot(
                    emptyList(),
                    targetAddresses.associateWith { AudioConnectionState.DISCONNECTED },
                )
            }
            for ((profile, lease) in leases) {
                if (lease != null && !lease.isValid) profiles[profile] = null
            }
            RawAudioSnapshot(true, spp, leObservable, bonded, profiles)
        } finally {
            leases.values.filterNotNull().forEach(ProfileProxyLease::close)
        }
    }

    @SuppressLint("MissingPermission") // connectionFacts checks BLUETOOTH_CONNECT (or legacy BLUETOOTH) first.
    private suspend fun requestProfileProxy(adapter: BluetoothAdapter, profileId: Int): ProfileProxyLease? =
        suspendCancellableCoroutine { continuation ->
            val request = ProfileProxyRequest(adapter, profileId, continuation)
            continuation.invokeOnCancellation { request.cancel() }
            try {
                if (!adapter.getProfileProxy(context, request, profileId)) request.unavailable()
            } catch (_: SecurityException) {
                request.unavailable()
            } catch (_: RuntimeException) {
                request.unavailable()
            }
        }

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
    }

    private class ProfileProxyRequest(
        private val adapter: BluetoothAdapter,
        private val profileId: Int,
        private val continuation: CancellableContinuation<ProfileProxyLease?>,
    ) : BluetoothProfile.ServiceListener {
        private enum class State { WAITING, DELIVERED, ABANDONED }
        private enum class ServiceArrival { DELIVER, DUPLICATE, REJECT }

        private val lock = Any()
        private var state = State.WAITING
        private var delivered: ProfileProxyLease? = null

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val lease = ProfileProxyLease(adapter, profileId, proxy)
            val action = synchronized(lock) {
                when (state) {
                    State.WAITING -> if (profile == profileId) {
                        state = State.DELIVERED
                        delivered = lease
                        ServiceArrival.DELIVER
                    } else {
                        ServiceArrival.REJECT
                    }

                    State.DELIVERED -> if (delivered?.owns(proxy) == true) ServiceArrival.DUPLICATE else ServiceArrival.REJECT

                    State.ABANDONED -> ServiceArrival.REJECT
                }
            }
            when (action) {
                ServiceArrival.DELIVER -> continuation.resume(lease) { _, resource, _ -> resource.close() }

                ServiceArrival.DUPLICATE -> Unit

                ServiceArrival.REJECT -> {
                    lease.close()
                    unavailable()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != profileId) return
            val result = synchronized(lock) {
                when (state) {
                    State.WAITING -> {
                        state = State.ABANDONED
                        null to true
                    }

                    State.DELIVERED -> delivered to false

                    State.ABANDONED -> null to false
                }
            }
            if (result.second) continuation.resume(null) else result.first?.invalidate()
        }

        fun unavailable() {
            val shouldResume = synchronized(lock) {
                if (state != State.WAITING) {
                    false
                } else {
                    state = State.ABANDONED
                    true
                }
            }
            if (shouldResume) continuation.resume(null)
        }

        fun cancel() {
            synchronized(lock) {
                if (state == State.WAITING) state = State.ABANDONED
            }
        }
    }

    private class ProfileProxyLease(
        private val adapter: BluetoothAdapter,
        private val profileId: Int,
        private val proxy: BluetoothProfile,
    ) {
        private val disconnected = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        val isValid: Boolean get() = !disconnected.get()
        fun owns(other: BluetoothProfile): Boolean = proxy === other

        @SuppressLint("MissingPermission") // Only queried after the public Bluetooth permission check.
        fun snapshot(targetAddresses: Set<String>): RawProfileSnapshot? {
            if (!isValid) return null
            val connected = try {
                proxy.connectedDevices.map { device -> device.address }.toSet()
            } catch (_: SecurityException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
            val states = try {
                (targetAddresses + connected).associateWith { address ->
                    when (proxy.getConnectionState(adapter.getRemoteDevice(address))) {
                        BluetoothProfile.STATE_CONNECTED -> AudioConnectionState.CONNECTED
                        BluetoothProfile.STATE_DISCONNECTED -> AudioConnectionState.DISCONNECTED
                        else -> return null
                    }
                }
            } catch (_: SecurityException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
            return RawProfileSnapshot(connected.toList(), states).takeIf { isValid }
        }

        fun invalidate() {
            disconnected.set(true)
        }

        fun close() {
            if (closed.compareAndSet(false, true)) runCatching { adapter.closeProfileProxy(profileId, proxy) }
        }
    }

    private companion object {
        const val PROFILE_PROBE_TIMEOUT_MILLIS = 2_500L
    }
}
