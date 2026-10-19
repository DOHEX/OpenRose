package io.github.dohex.openrose

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.core.transport.AudioProfileEvidence
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportFacts
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Reads only Android's public connected-profile lists; it does not inspect codecs or UI settings. */
internal class AndroidAudioProfiles(private val context: Context) {
    @SuppressLint("MissingPermission") // Permission is checked before accessing adapter/profile state.
    suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val permissionGranted = hasBluetoothPermission()
        val adapterEnabled = adapter != null && permissionGranted && runCatching { adapter.isEnabled }.getOrDefault(false)
        val sppAvailable = adapterEnabled &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            normalizedAddress(classicAddress) != null

        val profileEvidence = when {
            adapter == null || !adapterEnabled -> AudioProfileEvidence.UNKNOWN
            Build.VERSION.SDK_INT in Build.VERSION_CODES.S until Build.VERSION_CODES.TIRAMISU ->
                // API 31–32 lack a public LE_AUDIO profile ID, so classic-only evidence is insufficient.
                AudioProfileEvidence.UNKNOWN
            else -> inspectConnectedProfiles(adapter, deviceId, classicAddress)
        }
        TransportFacts(sppAvailable = sppAvailable, audioProfile = profileEvidence)
    }

    private suspend fun inspectConnectedProfiles(
        adapter: BluetoothAdapter,
        deviceId: DeviceId,
        classicAddress: String?,
    ): AudioProfileEvidence {
        val targetAddresses = listOfNotNull(normalizedAddress(deviceId.value), normalizedAddress(classicAddress)).toSet()
        if (targetAddresses.isEmpty()) return AudioProfileEvidence.UNKNOWN

        return withTimeoutOrNull(PROFILE_PROBE_TIMEOUT_MILLIS) {
            val profileIds = buildList {
                add(BluetoothProfile.A2DP)
                add(BluetoothProfile.HEADSET)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
            }
            val proxies = mutableListOf<ProfileProxyLease?>()
            try {
                profileIds.forEach { profileId -> proxies += requestProfileProxy(adapter, profileId) }
                // An unavailable proxy or any failed query leaves the evidence unknown.
                if (proxies.any { it == null }) return@withTimeoutOrNull AudioProfileEvidence.UNKNOWN
                val leases = proxies.map { requireNotNull(it) }
                val snapshotsByProfile = profileIds.zip(leases).associate { (profileId, lease) ->
                    val snapshot = lease.snapshot(targetAddresses)
                        ?: return@withTimeoutOrNull AudioProfileEvidence.UNKNOWN
                    profileId to snapshot
                }
                if (leases.any { !it.isValid }) return@withTimeoutOrNull AudioProfileEvidence.UNKNOWN

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val le = snapshotsByProfile[BluetoothProfile.LE_AUDIO] ?: return@withTimeoutOrNull AudioProfileEvidence.UNKNOWN
                    if (le.targetStates.values.any { it == BluetoothProfile.STATE_CONNECTED }) {
                        return@withTimeoutOrNull AudioProfileEvidence.LE_AUDIO
                    }
                    // A connected LE device whose identity cannot be tied to this scan is ambiguous.
                    if (le.connectedAddresses.isNotEmpty()) return@withTimeoutOrNull AudioProfileEvidence.UNKNOWN
                }

                val classicConnected = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
                    .any { profileId ->
                        snapshotsByProfile[profileId]?.targetStates?.values?.any { it == BluetoothProfile.STATE_CONNECTED } == true
                    }
                if (classicConnected) AudioProfileEvidence.CLASSIC else AudioProfileEvidence.UNKNOWN
            } catch (_: SecurityException) {
                AudioProfileEvidence.UNKNOWN
            } catch (_: RuntimeException) {
                AudioProfileEvidence.UNKNOWN
            } finally {
                proxies.filterNotNull().forEach(ProfileProxyLease::close)
            }
        } ?: AudioProfileEvidence.UNKNOWN
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
                    } else ServiceArrival.REJECT
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
                if (state != State.WAITING) false else {
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
        fun snapshot(targetAddresses: Set<String>): ProfileSnapshot? {
            if (!isValid) return null
            val connected = try {
                proxy.connectedDevices.map { device ->
                    normalizedAddress(device.address) ?: return null
                }.toSet()
            } catch (_: SecurityException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
            val states = try {
                // Other connected Classic devices do not determine this target's audio profile.
                (targetAddresses + connected).associateWith { address ->
                    proxy.getConnectionState(adapter.getRemoteDevice(address))
                        .takeIf { it == BluetoothProfile.STATE_CONNECTED || it == BluetoothProfile.STATE_DISCONNECTED }
                        ?: return null
                }
            } catch (_: SecurityException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
            if (connected.any { address -> states[address] != BluetoothProfile.STATE_CONNECTED }) return null
            return ProfileSnapshot(connected, states.filterKeys { it in targetAddresses }).takeIf { isValid }
        }

        fun invalidate() {
            disconnected.set(true)
        }

        fun close() {
            if (closed.compareAndSet(false, true)) runCatching { adapter.closeProfileProxy(profileId, proxy) }
        }
    }

    private data class ProfileSnapshot(
        val connectedAddresses: Set<String>,
        val targetStates: Map<String, Int>,
    )

    private companion object {
        const val PROFILE_PROBE_TIMEOUT_MILLIS = 2_500L

        fun normalizedAddress(value: String?): String? {
            val candidate = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return candidate.takeIf(BluetoothAdapter::checkBluetoothAddress)
        }
    }
}
