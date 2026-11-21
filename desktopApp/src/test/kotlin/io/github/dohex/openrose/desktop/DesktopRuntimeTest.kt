package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.BottomBarStyle
import io.github.dohex.openrose.ui.InterfaceStyle
import io.github.dohex.openrose.ui.PageTransition
import io.github.dohex.openrose.ui.UiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRuntimeTest {
    @Test
    fun windowsOffersTheSystemBluetoothSettingsPage() {
        assertEquals(listOf("explorer.exe", "ms-settings:bluetooth"), bluetoothSettingsCommand("Windows 11"))
    }

    @Test
    fun controlAndAppearanceSurviveRebuildingTheRuntime() = runTest {
        val path = "io/github/dohex/openrose/test/${UUID.randomUUID()}"
        val preferences = Preferences.userRoot().node(path)
        try {
            val first = DesktopRuntime(preferences, platform, backgroundScope)
            val chosenControl = UserSettings(forceBle = true)
            val chosenUi = UiSettings(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.DARK,
                pageTransition = PageTransition.NONE,
                backgroundBlurEnabled = false,
                bottomBarStyle = BottomBarStyle.FLOATING,
            )
            first.model.updateSettings(chosenControl)
            first.updateDesktopSettings(DesktopSettings(backgroundControl = false))
            val observed = first.model.state.value
            first.model.updateUiSettings(chosenUi)
            assertEquals(observed, first.model.state.value)

            val rebuilt = DesktopRuntime(Preferences.userRoot().node(path), platform, backgroundScope)
            assertEquals(chosenControl, rebuilt.model.state.value.settings)
            assertEquals(chosenUi, rebuilt.model.uiSettings.value)
            assertEquals(DesktopSettings(backgroundControl = false), rebuilt.desktopSettings.value)
            assertEquals("false", preferences.get("desktop.background_control", null))
            assertEquals("true", preferences.get("core.force_ble", null))
            assertEquals("material3", preferences.get("sharedUI.interface_style", null))
        } finally {
            preferences.removeNode()
            Preferences.userRoot().flush()
        }
    }

    @Test
    fun oldKeysDoNotRestoreObsoletePreferences() = runTest {
        val preferences = Preferences.userRoot().node("io/github/dohex/openrose/test/${UUID.randomUUID()}")
        try {
            preferences.put("force_ble", "true")
            preferences.put("interface_style", "material3")
            preferences.put("appearance", "dark")
            val runtime = DesktopRuntime(preferences, platform, backgroundScope)
            assertEquals(UserSettings(), runtime.model.state.value.settings)
            assertEquals(UiSettings(), runtime.model.uiSettings.value)
        } finally {
            preferences.removeNode()
            Preferences.userRoot().flush()
        }
    }

    @Test
    fun closingTheDesktopWaitsForItsBackgroundServicesToReleaseTheirResources() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val preferences = Preferences.userRoot().node("io/github/dohex/openrose/test/${UUID.randomUUID()}")
        val released = CompletableDeferred<Unit>()
        try {
            val runtime = DesktopRuntime(preferences, platform, scope)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(100)
                        released.complete(Unit)
                    }
                }
            }
            runtime.close()
            assertTrue(released.isCompleted)
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun missingOrUnknownDesktopValuesKeepBackgroundControlOn() {
        assertEquals(DesktopSettings(backgroundControl = true), DesktopSettingsCodec.decode(emptyMap()))
        assertEquals(DesktopSettings(backgroundControl = true), DesktopSettingsCodec.decode(mapOf("desktop.background_control" to "yes")))
        val off = DesktopSettings(backgroundControl = false)
        assertEquals(off, DesktopSettingsCodec.decode(DesktopSettingsCodec.encode(off)))
    }

    private val platform = PlatformState(permission = PermissionState.NOT_REQUIRED, hostPlatform = HostPlatform.LINUX)
}
