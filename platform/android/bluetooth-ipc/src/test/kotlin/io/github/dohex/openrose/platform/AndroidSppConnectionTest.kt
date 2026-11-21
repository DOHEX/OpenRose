package io.github.dohex.openrose.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothSocket
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class, shadows = [AndroidSppConnectionTest.RetrySocket::class])
class AndroidSppConnectionTest {
    @Test
    fun aRefusedCloseDuringOpenKeepsTheSocketForTheOwnersNextRelease() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val connection = AndroidSppConnection(adapter, "AA:BB:CC:DD:EE:FF", "00001101-0000-1000-8000-00805f9b34fb")
        assertFailsWith<IOException> { connection.open { throw IOException("discovery failed") } }
        assertFailsWith<IOException> { connection.close() }
        connection.close()
        connection.close()
        assertEquals(2, RetrySocket.attempts)
    }

    @Implements(BluetoothSocket::class)
    class RetrySocket : ShadowBluetoothSocket() {
        @Implementation
        public override fun close() {
            if (++attempts == 1) throw IOException("socket still open")
        }

        companion object {
            var attempts = 0
        }
    }
}
