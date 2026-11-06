package io.github.dohex.openrose

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.dohex.openrose.core.transport.AudioConnectionState
import io.github.dohex.openrose.core.transport.AudioProfile
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    manifest = Config.NONE,
    application = Application::class,
    shadows = [AndroidAudioProfilesTest.StallingLeAudioAdapter::class],
)
class AndroidAudioProfilesTest {
    @Test
    fun unavailableLeAudioProxyDoesNotDiscardExistingClassicConnections() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        shadowOf(device).setName("EarFree i5")
        shadowOf(adapter).apply {
            setState(BluetoothAdapter.STATE_ON)
            setBondedDevices(setOf(device))
            setProfileProxy(BluetoothProfile.A2DP, ConnectedProfile(device))
            setProfileProxy(BluetoothProfile.HEADSET, ConnectedProfile(device))
        }

        val snapshot = AndroidAudioProfiles(context).snapshot(setOf(device.address))

        for (profile in listOf(AudioProfile.A2DP, AudioProfile.HEADSET)) {
            assertEquals(listOf("AA:BB:CC:DD:EE:FF"), snapshot.profiles[profile]?.connectedAddresses)
            assertEquals(AudioConnectionState.CONNECTED, snapshot.profiles[profile]?.states?.get(device.address))
        }
        assertNull(snapshot.profiles[AudioProfile.LE_AUDIO])
    }

    /** Model a platform request that starts successfully but never delivers its LE Audio proxy. */
    @Implements(BluetoothAdapter::class)
    class StallingLeAudioAdapter : ShadowBluetoothAdapter() {
        @Implementation
        public override fun getProfileProxy(context: Context, listener: BluetoothProfile.ServiceListener, profile: Int): Boolean = if (profile == BluetoothProfile.LE_AUDIO) true else super.getProfileProxy(context, listener, profile)
    }

    private class ConnectedProfile(private val device: BluetoothDevice) : BluetoothProfile {
        override fun getConnectedDevices(): List<BluetoothDevice> = listOf(device)
        override fun getConnectionState(candidate: BluetoothDevice): Int = if (candidate == device) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = if (BluetoothProfile.STATE_CONNECTED in states) listOf(device) else emptyList()
    }
}
