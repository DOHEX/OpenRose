package io.github.dohex.openrose

import android.app.Application
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.PresenceObservation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class CompanionWakeStartupTest {
    @Test
    fun alreadyUnlockedColdStartRestoresPresenceObservation() {
        val context = RuntimeEnvironment.getApplication()
        val app = OpenRoseApplication()
        shadowOf(app).callAttach(context)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true)
        val manager = shadowOf(context.getSystemService(CompanionDeviceManager::class.java))
        val address = "11:22:33:44:55:66"
        manager.addAssociation(address)
        val store = AndroidSettingsStore { context.getSharedPreferences(AppPreferences.FILE, Context.MODE_PRIVATE) }
        store.saveHost(HostSettings(integration = IntegrationSettings(backgroundControlEnabled = true)), commit = true)

        app.onCreate()

        assertEquals(address, manager.lastObservingDevicePresenceDeviceAddress)
        assertEquals(PresenceObservation.OBSERVING, app.runtime.integration.value.platform.backgroundService.wake.presence)
    }
}
