package io.github.dohex.openrose.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.YieldRefusalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class PhoneCirculationAudioTest {
    @Test
    fun refusesToReleaseDuringAnOngoingVoiceCall() {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        val error = assertFailsWith<DriverException.YieldRefused> {
            runBlocking { PhoneCirculationAudio(context).yieldAudio("11:22:33:44:55:66") }
        }
        assertEquals(YieldRefusalKind.IN_CALL, error.kind)
    }

    @Test
    fun aHolderThatCannotReadItsAudioStateRefusesInsteadOfAnsweringNotHolding() {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val error = assertFailsWith<DriverException.YieldRefused> {
            runBlocking { PhoneCirculationAudio(context).isConnected("11:22:33:44:55:66") }
        }
        assertEquals(YieldRefusalKind.AUDIO_STATE_UNREADABLE, error.kind)
    }
}
